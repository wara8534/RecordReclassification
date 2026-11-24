import argparse
import json
import random
import re
from pathlib import Path

import torch
from peft import LoraConfig, PeftModel, get_peft_model, prepare_model_for_kbit_training
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig, set_seed


DATA = Path(__file__).resolve().parent
INSTRUCTION = (
    "기록물 공개 재분류를 검토한다. 공공기록물법 제35조의 재분류 절차와 예외, "
    "정보공개법 제9조 및 제14조의 공개 범위를 검토한다. 판단에 필요한 사실이 부족하면 검토가 필요하다. "
    "JSON 객체 하나로 응답한다. recommendation은 PUBLIC, PARTIAL, PRIVATE, NEEDS_REVIEW 중 하나다. "
    "criterionNumbers에는 적용되는 정보공개법 제9조 제1항 호수만 넣고, evidenceIds에는 입력 근거 ID를 넣는다. "
    "rationale에는 적용한 조항, 사실 근거와 기존 공개구분이 바뀌는 이유를 한국어로 쓴다."
)


def read_examples(filename):
    return [json.loads(line) for line in (DATA / filename).read_text(encoding="utf-8").splitlines()]


def prompt(row, tokenizer):
    record = {
        "reviewDate": row["date"],
        "input": {
            "stage": "WHOLE",
            "metadata": {"year": row["year"], "originalStatus": row["original"]},
            "evidence": [
                {"id": f"evidence-{i}", "text": fact}
                for i, fact in enumerate(row["facts"], 1)
            ],
        },
    }
    return tokenizer.apply_chat_template(
        [
            {"role": "system", "content": INSTRUCTION},
            {"role": "user", "content": json.dumps(record, ensure_ascii=False)},
        ],
        tokenize=True,
        add_generation_prompt=True,
        enable_thinking=False,
        return_tensors="pt",
    )


def evidence_ids(row):
    return [f"evidence-{i}" for i in row["evidence"]]


def encode(row, tokenizer):
    prefix = prompt(row, tokenizer)
    response = json.dumps({
        "recommendation": row["label"],
        "criterionNumbers": row["criteria"],
        "evidenceIds": evidence_ids(row),
        "rationale": row["reason"],
    }, ensure_ascii=False)
    target = tokenizer(response + tokenizer.eos_token, add_special_tokens=False, return_tensors="pt").input_ids
    input_ids = torch.cat((prefix, target), dim=1)
    return input_ids, target


@torch.inference_mode()
def evaluate(model, tokenizer, examples):
    model.eval()
    outcomes = []
    for row in examples:
        prefix = prompt(row, tokenizer).to(model.device)
        with torch.autocast(device_type="cuda", dtype=torch.bfloat16):
            result = model.generate(
                input_ids=prefix,
                attention_mask=torch.ones_like(prefix),
                use_model_defaults=False,
                generation_config=model.generation_config.__class__(
                    do_sample=False, max_new_tokens=240, use_cache=True,
                    eos_token_id=tokenizer.eos_token_id, pad_token_id=tokenizer.eos_token_id,
                ),
            )
        answer = tokenizer.decode(result[0, prefix.shape[1]:], skip_special_tokens=True).strip()
        try:
            parsed = json.loads(answer)
        except json.JSONDecodeError:
            parsed = {}
        if not isinstance(parsed, dict):
            parsed = {}
        rationale = parsed.get("rationale")
        outcomes.append({
            "id": row["id"],
            "expected": row["label"],
            "predicted": parsed.get("recommendation"),
            "classification": parsed.get("recommendation") == row["label"],
            "criteria": parsed.get("criterionNumbers") == row["criteria"],
            "evidence": parsed.get("evidenceIds") == evidence_ids(row),
            "citation_present": isinstance(rationale, str) and row["reference"] in re.sub(r"\s+", "", rationale),
            "response": answer,
        })
    return summarize(outcomes)


def summarize(outcomes):
    keys = ("classification", "criteria", "evidence", "citation_present")
    return {"cases": len(outcomes), **{key: sum(row[key] for row in outcomes) for key in keys}, "details": outcomes}


def train(model, examples, epochs):
    optimizer = torch.optim.AdamW((param for param in model.parameters() if param.requires_grad), lr=1e-4)
    shuffle = random.Random(42)
    loss_history = []
    model.train()
    for epoch in range(1, epochs + 1):
        shuffle.shuffle(examples)
        losses = []
        for input_ids, target in examples:
            optimizer.zero_grad(set_to_none=True)
            # The last prompt token predicts the first answer token.
            with torch.autocast(device_type="cuda", dtype=torch.bfloat16):
                logits = model(input_ids=input_ids.to(model.device), logits_to_keep=target.numel() + 1).logits
                loss = torch.nn.functional.cross_entropy(logits[0, :-1], target[0].to(model.device))
            if not torch.isfinite(loss):
                raise RuntimeError("Non-finite training loss")
            loss.backward()
            optimizer.step()
            losses.append(loss.item())
        loss_history.append(sum(losses) / len(losses))
        print(f"Epoch {epoch}/{epochs} loss={loss_history[-1]:.4f}", flush=True)
    return loss_history


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--epochs", type=int, default=4)
    parser.add_argument("--evaluate-only", action="store_true", help="Evaluate an adapter already saved in --output.")
    args = parser.parse_args()
    if not args.evaluate_only and (args.epochs < 1 or args.output.exists()):
        parser.error("Provide a positive epoch count and a new output directory.")
    if args.evaluate_only and not (args.output / "adapter_model.safetensors").is_file():
        parser.error("--evaluate-only requires a trained adapter.")
    if not torch.cuda.is_available() or not torch.cuda.is_bf16_supported():
        parser.error("CUDA with bfloat16 support is required.")

    set_seed(42)
    tokenizer = AutoTokenizer.from_pretrained(args.base, local_files_only=True)
    base = AutoModelForCausalLM.from_pretrained(
        args.base,
        local_files_only=True,
        device_map={"": 0},
        dtype=torch.bfloat16,
        quantization_config=BitsAndBytesConfig(
            load_in_4bit=True, bnb_4bit_quant_type="nf4",
            bnb_4bit_use_double_quant=True, bnb_4bit_compute_dtype=torch.bfloat16,
        ),
    )
    report_path = args.output / "evaluation.json"
    training_path = args.output / "training.json"
    if args.evaluate_only:
        model = PeftModel.from_pretrained(base, args.output, local_files_only=True)
        # Earlier pilot runs stored the training metadata alongside the evaluation.
        stats_path = training_path if training_path.is_file() else report_path
        saved = json.loads(stats_path.read_text(encoding="utf-8"))
        stats = {key: saved[key] for key in ("training_cases", "epochs", "training_loss")}
    else:
        train_rows = read_examples("train.jsonl")
        examples = [encode(row, tokenizer) for row in train_rows]
        base = prepare_model_for_kbit_training(base, gradient_checkpointing_kwargs={"use_reentrant": False})
        # Keep the large, frozen tied embedding in its original 16-bit precision.
        base.get_input_embeddings().to(torch.bfloat16)
        base.config.use_cache = False
        model = get_peft_model(base, LoraConfig(
            task_type="CAUSAL_LM", r=8, lora_alpha=16, lora_dropout=0.05,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj"],
        ))
        model.print_trainable_parameters()
        loss_history = train(model, examples, args.epochs)
        model.save_pretrained(args.output)
        model.gradient_checkpointing_disable()
        stats = {"training_cases": len(train_rows), "epochs": args.epochs, "training_loss": loss_history}
        training_path.write_text(json.dumps(stats, indent=2) + "\n", encoding="utf-8")

    eval_rows = read_examples("evaluation.jsonl")
    with model.disable_adapter():
        baseline = evaluate(model, tokenizer, eval_rows)
    print("Baseline evaluation complete", flush=True)
    adapted = evaluate(model, tokenizer, eval_rows)
    report = {
        **stats, "decoding": "greedy",
        "baseline": baseline, "adapted": adapted,
    }
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    for name in ("baseline", "adapted"):
        print(name, {key: value for key, value in report[name].items() if key != "details"}, flush=True)


if __name__ == "__main__":
    main()
