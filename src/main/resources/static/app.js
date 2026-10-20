"use strict";
const $ = (id) => document.getElementById(id);
const state = { records: [], policy: null, filename: "", sourceFile: null, page: 1, busy: false, dirty: false, editing: null };
const PAGE_SIZE = 10;
const statusClasses = { "공개": "public", "부분공개": "partial", "비공개": "private", "검토필요": "pending" };
// Keep review eligibility after a reviewer changes the final decision.
const canReview = (row) => row?.recommendation === "검토필요";
const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
function notify(message, type = "") {
  $("notice").textContent = message;
  $("notice").className = `notice ${type}`;
  $("notice").hidden = false;
}
async function api(url, options = {}) {
  const response = await fetch(url, options);
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || `요청을 처리하지 못했습니다 (${response.status}).`);
  }
  return response;
}
function jsonRequest(data) {
  return { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) };
}
async function task(action) {
  if (state.busy) return;
  state.busy = true;
  renderButtons();
  $("workspace").setAttribute("aria-busy", "true");
  try { await action(); }
  catch (error) {
    if (error instanceof TypeError && /fetch|network|load failed/i.test(error.message)) {
      // A failed upload does not necessarily mean the Java server is offline.
      try {
        const health = await fetch("/api/classification-policy", { cache: "no-store", signal: AbortSignal.timeout(5000) });
        if (!health.ok) throw new Error("Health check failed");
        $("connection").textContent = "로컬 서버 연결됨";
        $("connection-dot").classList.add("online");
        notify("서버는 정상 응답하지만 요청 전송이 실패했습니다. 엑셀을 닫고 파일을 다시 선택해 주세요. OneDrive 파일이면 PC에 다운로드한 복사본으로 다시 시도해 주세요.", "error");
      } catch {
        $("connection").textContent = "서버 연결 확인 필요";
        $("connection-dot").classList.remove("online");
        notify("서버 연결을 확인할 수 없습니다. Java 서버 터미널의 오류를 확인한 후 다시 시도해 주세요.", "error");
      }
    } else {
      notify(error.message, "error");
    }
  }
  finally { state.busy = false; $("workspace").removeAttribute("aria-busy"); renderButtons(); }
}
function renderButtons() {
  for (const id of ["upload-button", "template-button", "policy-nav"]) $(id).disabled = state.busy;
  for (const id of ["classify-button", "export-button"]) $(id).disabled = state.busy || !state.records.length;
  $("classify-button").textContent = state.busy ? "처리 중…" : "✧ 다시 분류";
  document.querySelectorAll(".review-button").forEach(button => { button.disabled = state.busy; });
}
function filteredRecords() {
  const query = $("search-input").value.trim().toLocaleLowerCase();
  return state.records.filter(row =>
    (!query || [row.id, row.title, row.department].some(v => v.toLocaleLowerCase().includes(query))) &&
    (!$("status-filter").value || row.decision === $("status-filter").value) &&
    (!$("review-filter").value || (canReview(row) && row.reviewed === ($("review-filter").value === "done"))));
}
function render() {
  $("total-count").textContent = state.records.length.toLocaleString();
  for (const [status, name] of Object.entries(statusClasses)) $(name + "-count").textContent = state.records.filter(row => row.decision === status).length.toLocaleString();
  $("file-name").textContent = state.filename || "아직 불러온 파일이 없습니다";
  $("file-name").title = state.filename;
  $("list-count").textContent = state.records.length;
  const reviewable = state.records.filter(canReview);
  $("review-progress").textContent = `검토완료 ${reviewable.filter(row => row.reviewed).length} / 검토대상 ${reviewable.length}`;
  const filtered = filteredRecords();
  const pages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  state.page = Math.min(state.page, pages);
  const start = (state.page - 1) * PAGE_SIZE;
  $("records-body").innerHTML = filtered.slice(start, start + PAGE_SIZE).map(row => `
    <tr><td>${escapeHtml(row.id)}</td><td><span class="row-title">${escapeHtml(row.title)}</span><span class="row-dept">${escapeHtml(row.department || "부서 미입력")}</span></td>
    <td>${escapeHtml(row.year || "—")}</td><td>${escapeHtml(row.originalStatus || "—")}</td>
    <td><span class="badge ${statusClasses[row.decision]}">${escapeHtml(row.decision)}</span></td>
    <td>${canReview(row) ? `<span class="review-state ${row.reviewed ? "done" : ""}">${row.reviewed ? "✓ 검토완료" : "미검토"}</span>` : '<span class="review-state" aria-label="검토 대상 아님">—</span>'}</td>
    <td>${canReview(row) ? `<button class="review-button" data-id="${escapeHtml(row.id)}" aria-label="${escapeHtml(row.title)} 검토">검토</button>` : '<span class="review-state" aria-label="검토 대상 아님">—</span>'}</td></tr>`).join("");
  $("empty-state").hidden = filtered.length > 0;
  $("empty-state").querySelector("h3").textContent = state.records.length ? "검색 조건에 맞는 기록물이 없습니다" : "분류할 기록물을 기다리고 있어요";
  $("empty-state").querySelector("p").textContent = state.records.length ? "검색어나 필터를 변경해 보세요." : "입력 양식에 내용을 작성한 뒤 업로드하면 결과 엑셀이 생성됩니다.";
  $("page-info").textContent = filtered.length ? `전체 ${filtered.length.toLocaleString()}건 중 ${start + 1}–${Math.min(start + PAGE_SIZE, filtered.length)}건` : "전체 0건";
  $("page-number").textContent = `${state.page} / ${pages}`;
  $("previous-page").disabled = state.page <= 1;
  $("next-page").disabled = state.page >= pages;
  renderButtons();
}
function replaceRecords(records, filename) {
  state.records = records;
  state.filename = filename;
  state.page = 1;
  state.dirty = true;
  $("search-input").value = $("status-filter").value = $("review-filter").value = "";
  render();
}
function mayReplace() {
  return !state.dirty || confirm("현재 작업을 새 데이터로 교체할까요? 보관할 결과는 먼저 다운로드해 주세요.");
}
async function upload(file) {
  if (!file || state.busy) return;
  if (!/\.(xlsx|xls)$/i.test(file.name)) return notify(".xlsx 또는 .xls 파일을 선택해 주세요.", "error");
  if (file.size > 10 * 1024 * 1024) return notify("파일 크기는 최대 10MB입니다.", "error");
  if (!mayReplace()) return;
  await task(async () => {
    notify("엑셀 파일을 읽고 있습니다. 잠시 기다려 주세요.", "info");
    let bytes;
    try {
      // Snapshot the selected file before uploading. Cloud sync or an Excel save
      // can otherwise change the file while the browser is sending FormData.
      bytes = await file.arrayBuffer();
    } catch {
      throw new Error("선택한 엑셀 파일을 읽을 수 없습니다. 엑셀을 닫고 OneDrive에서 '이 장치에 항상 유지'를 선택하거나 PC의 다른 폴더에 복사한 뒤 다시 선택해 주세요.");
    }
    if (!bytes.byteLength) throw new Error("빈 파일입니다. 파일이 완전히 다운로드되었는지 확인해 주세요.");
    if (bytes.byteLength > 10 * 1024 * 1024) throw new Error("파일 크기는 최대 10MB입니다.");
    const data = new FormData();
    const mime = /\.xlsx$/i.test(file.name)
      ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      : "application/vnd.ms-excel";
    const snapshot = new Blob([bytes], { type: mime });
    data.append("file", snapshot, file.name);
    notify("엑셀 내용을 분류하고 있습니다.", "info");
    const result = await (await api("/api/records/import", { method: "POST", body: data })).json();
    state.sourceFile = snapshot;
    replaceRecords(result.records, result.filename);
    notify(`${result.records.length}건의 분류가 완료되었습니다. 화면에서 결과를 확인한 뒤 ‘결과 다운로드’를 눌러 엑셀로 저장하세요.${result.warnings.length ? " " + result.warnings.join(" ") : ""}`, result.warnings.length ? "info" : "");
  });
}
async function exportWorkbook() {
  if (!state.sourceFile) throw new Error("결과를 작성할 원본 파일이 없습니다. 엑셀 파일을 업로드해 주세요.");
  const data = new FormData();
  data.append("file", state.sourceFile, state.filename);
  data.append("records", new Blob([JSON.stringify({ records: state.records })], { type: "application/json" }), "records.json");
  const extension = /\.xls$/i.test(state.filename) ? ".xls" : ".xlsx";
  await download(await api("/api/records/export", { method: "POST", body: data }),
    state.filename.replace(/\.(xlsx|xls)$/i, "") + "_분류결과" + extension);
}
async function download(response, filename) {
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}
$("upload-button").addEventListener("click", () => $("file-input").click());
$("file-input").addEventListener("change", async event => { await upload(event.target.files[0]); event.target.value = ""; });
for (const type of ["dragenter", "dragover"]) $("dropzone").addEventListener(type, event => { event.preventDefault(); $("dropzone").classList.add("dragover"); });
for (const type of ["dragleave", "drop"]) $("dropzone").addEventListener(type, event => { event.preventDefault(); $("dropzone").classList.remove("dragover"); });
$("dropzone").addEventListener("drop", event => {
  if (event.dataTransfer.files.length !== 1) return notify("한 번에 엑셀 파일 하나를 선택해 주세요.", "error");
  upload(event.dataTransfer.files[0]);
});
$("template-button").addEventListener("click", () => task(async () => {
  await download(await api("/api/records/template"), "서식.xlsx");
  notify("첨부 서식 다운로드를 요청했습니다. 15개 열 이름을 유지하고, 앞의 12개 입력 열을 작성해 주세요.");
}));
$("classify-button").addEventListener("click", () => task(async () => {
  state.records = await (await api("/api/records/classify", jsonRequest({ records: state.records }))).json();
  state.dirty = true;
  render();
  notify("다시 분류했습니다. 검토 완료 항목은 유지했습니다. 결과를 확인한 뒤 ‘결과 다운로드’를 눌러 저장하세요.");
}));
$("export-button").addEventListener("click", () => task(async () => {
  await exportWorkbook();
  // A browser download request cannot prove that the user saved the file.
  notify(`전체 ${state.records.length}건의 다운로드를 요청했습니다. 검토대상 중 미검토 ${state.records.filter(row => canReview(row) && !row.reviewed).length}건도 포함됩니다. 파일이 저장되었는지 확인해 주세요.`);
}));
for (const id of ["search-input", "status-filter", "review-filter"]) $(id).addEventListener(id === "search-input" ? "input" : "change", () => { state.page = 1; render(); });
$("previous-page").addEventListener("click", () => { state.page--; render(); });
$("next-page").addEventListener("click", () => { state.page++; render(); });
$("records-body").addEventListener("click", event => {
  const button = event.target.closest("[data-id]");
  if (!button || state.busy) return;
  const row = state.records.find(item => item.id === button.dataset.id);
  if (!canReview(row)) return;
  state.editing = row.id;
  $("review-title").textContent = row.title;
  $("review-meta").textContent = `${row.id} · ${row.department || "부서 미입력"} · ${row.year || "연도 미입력"} · 기존: ${row.originalStatus || "미입력"}`;
  $("review-content").textContent = `기록물분류: ${row.category || "미입력"}\n키워드: ${row.keywords || "미입력"}\n\n${row.content || "입력된 내용이 없습니다."}`;
  $("review-recommendation").textContent = `자동분류 결과: ${row.recommendation} · 엑셀 ${row.sourceRow}행`;
  $("decision-input").value = row.decision;
  $("decision-input").setCustomValidity("");
  $("reason-input").setCustomValidity("");
  $("reason-input").value = row.reason;
  $("non-disclosure-input").value = row.nonDisclosureReason;
  $("non-disclosure-input").setCustomValidity("");
  $("reviewed-input").checked = row.reviewed;
  $("review-dialog").showModal();
});
$("review-form").addEventListener("submit", event => {
  event.preventDefault();
  const row = state.records.find(item => item.id === state.editing);
  if (state.busy || !canReview(row)) return;
  if ($("reviewed-input").checked && $("decision-input").value === "검토필요") {
    $("decision-input").setCustomValidity("공개, 부분공개, 비공개 중 하나를 선택해 주세요.");
    $("decision-input").reportValidity();
    return;
  }
  if (!$("reason-input").value.trim()) {
    $("reason-input").setCustomValidity("검토 의견을 입력해 주세요.");
    $("reason-input").reportValidity();
    return;
  }
  if (["비공개", "부분공개"].includes($("decision-input").value) && !$("non-disclosure-input").value.trim()) {
    $("non-disclosure-input").setCustomValidity("비공개·부분공개 결과에는 비공개사유를 작성해 주세요.");
    $("non-disclosure-input").reportValidity();
    return;
  }
  row.decision = $("decision-input").value;
  row.reason = $("reason-input").value.trim();
  row.nonDisclosureReason = row.decision === "공개" ? "" : $("non-disclosure-input").value.trim();
  row.reviewed = $("reviewed-input").checked;
  state.dirty = true;
  $("review-dialog").close();
  render();
  notify("검토 결과를 적용했습니다. 결과 다운로드로 파일에 보관해 주세요.");
});
$("decision-input").addEventListener("change", () => {
  $("decision-input").setCustomValidity("");
  $("non-disclosure-input").setCustomValidity("");
});
$("non-disclosure-input").addEventListener("input", () => $("non-disclosure-input").setCustomValidity(""));
$("reviewed-input").addEventListener("change", () => $("decision-input").setCustomValidity(""));
$("reason-input").addEventListener("input", () => $("reason-input").setCustomValidity(""));
function renderPolicy() {
  const policy = state.policy;
  $("policy-version").textContent = policy.version;
  $("policy-method").textContent = policy.method;
  $("policy-list").innerHTML = policy.criteria.map(c => `<article class="policy-card">
    <h3>제9조제1항제${c.number}호 · ${escapeHtml(c.title)}</h3>
    <p>${escapeHtml(c.requirement)}</p><p class="policy-exception">${escapeHtml(c.exceptionNote)}</p>
    <details><summary>보조 탐지어 보기</summary><p>${c.keywords.map(escapeHtml).join(", ")}</p></details>
    </article>`).join("");
  $("policy-notes").innerHTML = policy.notes.map(note => `<li>${escapeHtml(note)}</li>`).join("");
}
$("policy-nav").addEventListener("click", () => task(async () => {
  if (!state.policy) state.policy = await (await api("/api/classification-policy")).json();
  renderPolicy();
  $("policy-dialog").showModal();
}));
$("guide-nav").addEventListener("click", () => $("guide-dialog").showModal());
document.querySelectorAll("[data-close]").forEach(button => button.addEventListener("click", () => $(button.dataset.close).close()));
window.addEventListener("beforeunload", event => { if (state.dirty) { event.preventDefault(); event.returnValue = ""; } });
async function initialize() {
  await task(async () => {
    state.policy = await (await api("/api/classification-policy")).json();
    $("connection").textContent = "로컬 서버 연결됨";
    $("connection-dot").classList.add("online");
  });
  if (!$("connection-dot").classList.contains("online")) $("connection").textContent = "서버 연결 확인 필요";
  render();
}
initialize();
