import { attachmentAccept, attachmentError, attachmentPath, attachmentRuns, attachmentSequencePath, linkAttachments, sourceKey } from "./attachments.js";

const $ = (id) => document.getElementById(id);
const state = { records: [], filename: "", sourceFile: null, page: 1, pageSize: 10, busy: false, dirty: false, editing: null,
  editedRows: new Set(), aiEnabled: false, aiResults: new Map(), attachmentFiles: [], attachmentRuns: [], attachments: linkAttachments([]),
  batchRunning: false, stopBatch: false };
const statusClasses = { "공개": "public", "부분공개": "partial", "비공개": "private", "검토필요": "pending" };
const aiRecommendations = { PUBLIC: "공개", PRIVATE: "비공개", PARTIAL: "부분공개", NEEDS_REVIEW: "검토필요" };
const disclosureDecisions = new Set(["공개", "부분공개", "비공개"]);
function disclosureChange(originalStatus, proposedStatus) {
  const original = originalStatus.replace(/\s+/g, "");
  return disclosureDecisions.has(original) && disclosureDecisions.has(proposedStatus) && original !== proposedStatus
    ? `${original} → ${proposedStatus}` : "";
}
// Keep review eligibility after a reviewer changes the final decision.
const canReview = row => Boolean(row && (state.aiEnabled || row.reviewed || row.decision === "검토필요"
  || row.recommendation === "검토필요" || disclosureChange(row.originalStatus, row.recommendation)));
const sourceLabel = (row) => ({ FOLDER: "기록물철", ITEM: "기록물건" })[row.sourceSheet] || "";
const linkedFiles = row => state.attachments.matches.get(sourceKey(row)) || [];
const linkIssue = row => state.attachments.issues.get(sourceKey(row));
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
        const health = await fetch("/api/records/ai/status", { cache: "no-store", signal: AbortSignal.timeout(5000) });
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
  for (const id of ["upload-button", "template-button", "attachment-folder-button", "attachment-folder-clear", "attachment-run"]) $(id).disabled = state.busy;
  for (const id of ["classify-button", "export-button"]) $(id).disabled = state.busy || !state.records.length;
  $("classify-button").textContent = state.busy ? "처리 중…" : "✧ 다시 분류";
  $("ai-run").disabled = $("ai-attachments").disabled = $("ai-fill").disabled = $("ai-complete").disabled = state.busy;
  $("ai-batch").disabled = state.busy || !batchTargets().length;
  $("ai-bulk-complete").disabled = state.busy || !state.aiEnabled || !state.aiResults.size ||
    !state.records.some(row => !row.reviewed && acceptedAiProposal(row));
  $("ai-batch-stop").hidden = !state.batchRunning;
  $("ai-batch-stop").disabled = state.stopBatch;
  document.querySelectorAll(".review-button").forEach(button => { button.disabled = state.busy; });
}
function filteredRecords() {
  const query = $("search-input").value.trim().toLocaleLowerCase();
  const changeQuery = query.replace(/\s+/g, "").replaceAll("->", "→");
  const status = $("status-filter").value;
  const review = $("review-filter").value;
  const changeFilter = $("change-filter").value;
  return state.records.filter(row => {
    if (status && displayedDecision(row) !== status) return false;
    if (review && (!canReview(row) || row.reviewed !== (review === "done"))) return false;
    const change = changeFilter || query ? recordChange(row) : "";
    if (changeFilter === "has" && !change) return false;
    if (changeFilter === "none" && change) return false;
    if (changeFilter.includes("→") && change !== changeFilter) return false;
    if (!query || [row.id, row.title, row.department, row.sourceSequence || ""].some(value => value.toLocaleLowerCase().includes(query))) return true;
    if (!change) return false;
    const compactChange = change.replaceAll(" ", "");
    return changeQuery.includes("→") ? compactChange === changeQuery : compactChange.includes(changeQuery);
  });
}
function displayedDecision(row, result = state.aiResults.get(sourceKey(row))?.result) {
  if (!state.aiEnabled || row.reviewed) return row.decision;
  return result?.status === "READY" ? aiRecommendations[result.proposal.recommendation] : "";
}
function recordChange(row, result = state.aiResults.get(sourceKey(row))?.result) {
  if (row.reviewed) return "";
  const proposed = result?.status === "READY" ? aiRecommendations[result.proposal.recommendation] : row.recommendation;
  return disclosureChange(row.originalStatus, proposed);
}
function recordDetails(row) {
  const parts = [sourceLabel(row), row.department];
  if (row.sourceSequence) parts.push(`순번 ${row.sourceSequence}`);
  const path = attachmentSequencePath(row);
  if (row.sourceSheet === "ITEM" && path) parts.push(`철/건 ${path}`);
  if (linkIssue(row)) parts.push("첨부 확인 필요");
  else if (linkedFiles(row).length) parts.push(`첨부 ${linkedFiles(row).length}개`);
  const result = state.aiResults.get(sourceKey(row))?.result;
  const change = recordChange(row, result);
  if (result && !row.reviewed && result.status !== "READY") parts.push("AI 검토 보류");
  const reason = result?.status === "READY" ? result.proposal.rationale
    : row.reason?.split("\n").find(line => /^제9조/.test(line)) || row.reason;
  return { context: parts.filter(Boolean).join(" · "), change, reason };
}
function render() {
  hideChangeReason();
  $("total-count").textContent = state.records.length.toLocaleString();
  const counts = new Map();
  for (const row of state.records) {
    const decision = displayedDecision(row);
    if (decision) counts.set(decision, (counts.get(decision) || 0) + 1);
  }
  for (const [status, name] of Object.entries(statusClasses)) $(name + "-count").textContent = (counts.get(status) || 0).toLocaleString();
  $("file-name").textContent = state.filename || "아직 불러온 파일이 없습니다";
  $("file-name").title = state.filename;
  $("list-count").textContent = state.records.length;
  const reviewable = state.records.filter(canReview);
  $("review-progress").textContent = `검토완료 ${reviewable.filter(row => row.reviewed).length} / 검토대상 ${reviewable.length}`;
  const filtered = filteredRecords();
  const pages = Math.ceil(filtered.length / state.pageSize);
  state.page = pages ? Math.min(state.page, pages) : 1;
  const start = (state.page - 1) * state.pageSize;
  const end = Math.min(start + state.pageSize, filtered.length);
  $("records-body").innerHTML = filtered.slice(start, end).map(row => {
    const { context, change, reason } = recordDetails(row);
    const decision = displayedDecision(row);
    const decisionLabel = state.aiEnabled ? (row.reviewed ? "담당자 확정" : "AI 추천") : "공개여부";
    const decisionCell = decision ? `<span class="badge ${statusClasses[decision]}" title="${decisionLabel}">${escapeHtml(decision)}</span>`
      : '<span class="review-state" aria-label="AI 추천 결과 없음">—</span>';
    const reasonAttributes = change && reason?.trim()
      ? ` tabindex="0" data-change="${escapeHtml(change)}" data-change-reason="${escapeHtml(reason.replace(/\s+/g, " ").trim())}"` : "";
    return `<tr><td>${escapeHtml(row.id)}</td><td><span class="row-title"${reasonAttributes}>${escapeHtml(row.title)}</span><span class="row-dept">${escapeHtml(context || "부서 미입력")}</span></td>
    <td>${change ? `<span class="change-proposal">${escapeHtml(change)}</span>` : '<span class="review-state">—</span>'}</td>
    <td>${escapeHtml(row.year || "—")}</td><td>${escapeHtml(row.originalStatus || "—")}</td>
    <td>${decisionCell}</td>
    <td>${canReview(row) ? `<span class="review-state ${row.reviewed ? "done" : ""}">${row.reviewed ? "✓ 검토완료" : "미검토"}</span>` : '<span class="review-state" aria-label="검토 대상 아님">—</span>'}</td>
    <td>${canReview(row) ? `<button class="review-button" data-source="${escapeHtml(sourceKey(row))}" aria-label="${escapeHtml(row.title)} 검토">검토</button>` : '<span class="review-state" aria-label="검토 대상 아님">—</span>'}</td></tr>`;
  }).join("");
  $("empty-state").hidden = filtered.length > 0;
  $("empty-state").querySelector("h3").textContent = state.records.length ? "검색 조건에 맞는 기록물이 없습니다" : "분류할 기록물을 기다리고 있어요";
  $("empty-state").querySelector("p").textContent = state.records.length ? "검색어나 필터를 변경해 보세요." : "입력 양식에 내용을 작성한 뒤 업로드하면 결과 엑셀이 생성됩니다.";
  const recordCount = state.records.length === filtered.length ? "전체" : "검색 결과";
  $("page-info").textContent = pages > 1
    ? `${recordCount} ${filtered.length.toLocaleString()}건 중 ${start + 1}–${end}건`
    : `${recordCount} ${filtered.length}건${pages === 1 ? " · 1페이지" : ""}`;
  $("page-controls").hidden = pages === 0;
  $("page-number").max = Math.max(1, pages);
  $("page-number").value = pages ? state.page : "";
  $("page-count").textContent = pages;
  $("page-number").disabled = $("page-go").disabled = pages <= 1;
  $("previous-page").disabled = state.page <= 1;
  $("next-page").disabled = state.page >= pages;
  renderButtons();
}
function replaceRecords(records, filename) {
  if (state.sourceFile) state.attachmentFiles = [];
  state.records = records;
  state.editedRows.clear();
  state.filename = filename;
  state.page = 1;
  state.dirty = true;
  $("search-input").value = $("status-filter").value = $("review-filter").value = $("change-filter").value = "";
  refreshAttachmentRuns();
}
function refreshAttachmentRuns() {
  state.attachmentRuns = attachmentRuns(state.filename, state.attachmentFiles);
  const select = $("attachment-run");
  select.replaceChildren(new Option("등록 시각을 선택하세요", ""),
    ...state.attachmentRuns.map(run => new Option(run.label, run.label)));
  select.value = state.attachmentRuns.length === 1 ? state.attachmentRuns[0].label : "";
  $("attachment-run-picker").hidden = state.attachmentRuns.length < 2;
  refreshAttachmentLinks();
}
function refreshAttachmentLinks() {
  const run = state.attachmentRuns.find(item => item.label === $("attachment-run").value);
  state.attachments = linkAttachments(state.records, run);
  state.aiResults.clear();
  $("ai-batch-progress").textContent = "";
  const { matches, issues, unmatched } = state.attachments;
  const count = state.attachmentFiles.length;
  $("attachment-folder-clear").hidden = !count;
  const folderName = count ? attachmentPath(state.attachmentFiles[0]).split("/")[0] : "";
  const linkedCount = [...matches.values()].reduce((total, files) => total + files.length, 0);
  $("attachment-status").textContent = !count ? "폴더를 선택하세요."
    : !state.records.length ? `${folderName} · 파일 ${count}개를 선택했습니다. 엑셀을 불러오면 연결합니다.`
    : !state.attachmentRuns.length ? "이 엑셀과 일치하는 파일명/등록시각 폴더를 찾지 못했습니다."
    : !run ? "이 엑셀의 등록 시각이 여러 개입니다. 검토할 등록 시각을 선택하세요."
    : `${run.label} · ${matches.size}건에 ${linkedCount}개 연결 · 미연결 ${unmatched.length}개 · 확인 필요 ${issues.size}건`;
  const rowsBySource = new Map(state.records.map(row => [sourceKey(row), row]));
  const lines = [...issues].slice(0, 20).map(([key, issue]) => {
    const row = rowsBySource.get(key);
    return `${sourceLabel(row) || "엑셀"} ${row.sourceRow}행 · ${issue}`;
  });
  lines.push(...unmatched.slice(0, 20 - lines.length).map(({ path, reason }) => `${path} · ${reason}`));
  const messageCount = issues.size + unmatched.length;
  $("attachment-issues").hidden = !state.records.length || !messageCount;
  if (messageCount > lines.length) lines.push(`외 ${messageCount - lines.length}개`);
  $("attachment-issue-list").replaceChildren(...lines.map(message => {
    const item = document.createElement("li");
    item.textContent = message;
    return item;
  }));
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
    notify(state.aiEnabled ? "기록물 목록을 불러오고 있습니다." : "엑셀 내용을 분류하고 있습니다.", "info");
    const result = await (await api("/api/records/import", { method: "POST", body: data })).json();
    replaceRecords(result.records, result.filename);
    state.sourceFile = snapshot;
    const message = state.aiEnabled ? `${result.records.length.toLocaleString()}건을 불러왔습니다. 첨부가 있으면 폴더를 선택한 뒤 ‘일괄 AI 검토 시작’을 눌러 주세요.`
      : `${result.records.length}건의 분류가 완료되었습니다. 화면에서 결과를 확인한 뒤 ‘결과 다운로드’를 눌러 엑셀로 저장하세요.`;
    notify(message + (result.warnings.length ? " " + result.warnings.join(" ") : ""), result.warnings.length ? "info" : "");
  });
}
async function exportWorkbook() {
  if (!state.sourceFile) throw new Error("결과를 작성할 원본 파일이 없습니다. 엑셀 파일을 업로드해 주세요.");
  const data = new FormData();
  data.append("file", state.sourceFile, state.filename);
  const edits = state.records.filter(row => state.editedRows.has(sourceKey(row)));
  data.append("edits", new Blob([JSON.stringify({ edits })], { type: "application/json" }), "edits.json");
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
$("attachment-folder-button").addEventListener("click", () => $("attachment-folder-input").click());
$("attachment-folder-input").addEventListener("change", event => {
  if (state.busy) return;
  state.attachmentFiles = [...event.target.files];
  event.target.value = "";
  refreshAttachmentRuns();
});
$("attachment-folder-clear").addEventListener("click", () => {
  state.attachmentFiles = [];
  refreshAttachmentRuns();
});
$("attachment-run").addEventListener("change", refreshAttachmentLinks);
for (const type of ["dragenter", "dragover"]) $("dropzone").addEventListener(type, event => { event.preventDefault(); $("dropzone").classList.add("dragover"); });
for (const type of ["dragleave", "drop"]) $("dropzone").addEventListener(type, event => { event.preventDefault(); $("dropzone").classList.remove("dragover"); });
$("dropzone").addEventListener("drop", event => {
  if (event.dataTransfer.files.length !== 1) return notify("한 번에 엑셀 파일 하나를 선택해 주세요.", "error");
  upload(event.dataTransfer.files[0]);
});
$("template-button").addEventListener("click", () => task(async () => {
  await download(await api("/api/records/template"), "기록물등록_문서류·시청각류일괄등록서식.xlsx");
  notify("등록 서식의 다운로드를 요청했습니다. 기록물철·기록물건 시트의 5행부터 작성해 주세요.");
}));
$("classify-button").addEventListener("click", () => task(async () => {
  state.records = await (await api("/api/records/classify", jsonRequest({ records: state.records }))).json();
  state.editedRows = new Set(state.records.filter(row => row.reviewed).map(sourceKey));
  state.dirty = true;
  render();
  notify("다시 분류했습니다. 검토 완료 항목은 유지했습니다. 결과를 확인한 뒤 ‘결과 다운로드’를 눌러 저장하세요.");
}));
$("export-button").addEventListener("click", () => task(async () => {
  await exportWorkbook();
  // A browser download request cannot prove that the user saved the file.
  notify(`전체 ${state.records.length}건의 다운로드를 요청했습니다. 검토대상 중 미검토 ${state.records.filter(row => canReview(row) && !row.reviewed).length}건도 포함됩니다. 파일이 저장되었는지 확인해 주세요.`);
}));
for (const id of ["search-input", "status-filter", "review-filter", "change-filter"]) $(id).addEventListener(id === "search-input" ? "input" : "change", () => { state.page = 1; render(); });
function goToPage(page) {
  const total = Number($("page-count").textContent);
  if (!Number.isInteger(page) || page < 1 || page > total) return;
  state.page = page;
  render();
}
$("previous-page").addEventListener("click", () => goToPage(state.page - 1));
$("next-page").addEventListener("click", () => goToPage(state.page + 1));
$("page-size").addEventListener("change", event => {
  state.pageSize = Number(event.target.value);
  state.page = 1;
  render();
});
$("page-jump").addEventListener("submit", event => {
  event.preventDefault();
  const input = $("page-number");
  if (input.disabled || !input.reportValidity()) return;
  goToPage(input.valueAsNumber);
});
let changeReasonAnchor = null;
let changeReasonTimer;
function hideChangeReason() {
  clearTimeout(changeReasonTimer);
  if (!changeReasonAnchor) return;
  changeReasonAnchor.removeAttribute("aria-describedby");
  changeReasonAnchor = null;
  $("change-reason-tooltip").hidden = true;
}
function showChangeReason(anchor) {
  hideChangeReason();
  changeReasonAnchor = anchor;
  const tooltip = $("change-reason-tooltip");
  $("change-reason-heading").textContent = `변경 제안 · ${anchor.dataset.change}`;
  $("change-reason-text").textContent = anchor.dataset.changeReason;
  tooltip.hidden = false;
  anchor.setAttribute("aria-describedby", tooltip.id);
  positionChangeReason();
}
function positionChangeReason() {
  if (!changeReasonAnchor) return;
  const tooltip = $("change-reason-tooltip");
  const bounds = changeReasonAnchor.getBoundingClientRect();
  if (bounds.bottom <= 0 || bounds.top >= window.innerHeight) {
    hideChangeReason();
    return;
  }
  const gutter = 8;
  const left = Math.min(bounds.left, document.documentElement.clientWidth - tooltip.offsetWidth - gutter);
  const below = bounds.bottom + gutter;
  const top = below + tooltip.offsetHeight + gutter <= window.innerHeight
    ? below : bounds.top - tooltip.offsetHeight - gutter;
  tooltip.style.left = `${Math.max(gutter, left)}px`;
  tooltip.style.top = `${Math.max(gutter, top)}px`;
}
$("records-body").addEventListener("pointerover", event => {
  if (event.pointerType === "touch") return;
  const anchor = event.target.closest("[data-change-reason]");
  if (anchor) showChangeReason(anchor);
});
$("records-body").addEventListener("pointerout", event => {
  if (event.pointerType === "touch" || !changeReasonAnchor ||
    !changeReasonAnchor.contains(event.target) || document.activeElement === changeReasonAnchor) return;
  changeReasonTimer = setTimeout(hideChangeReason, 160);
});
$("records-body").addEventListener("focusin", event => {
  const anchor = event.target.closest("[data-change-reason]");
  if (anchor) showChangeReason(anchor);
});
$("records-body").addEventListener("focusout", event => {
  if (event.target === changeReasonAnchor) hideChangeReason();
});
$("change-reason-tooltip").addEventListener("pointerenter", () => clearTimeout(changeReasonTimer));
$("change-reason-tooltip").addEventListener("pointerleave", () => {
  if (document.activeElement !== changeReasonAnchor) hideChangeReason();
});
document.addEventListener("keydown", event => {
  if (event.key === "Escape") hideChangeReason();
});
window.addEventListener("scroll", positionChangeReason, true);
window.addEventListener("resize", positionChangeReason);
$("records-body").addEventListener("click", event => {
  const button = event.target.closest("[data-source]");
  if (!button || state.busy) return;
  const row = state.records.find(item => sourceKey(item) === button.dataset.source);
  if (!canReview(row)) return;
  state.editing = sourceKey(row);
  $("review-title").textContent = row.title;
  $("review-meta").textContent = `${row.id} · ${row.department || "부서 미입력"} · ${row.year || "연도 미입력"} · 기존: ${row.originalStatus || "미입력"}`;
  $("review-content").textContent = `기록물분류: ${row.category || "미입력"}\n키워드: ${row.keywords || "미입력"}\n\n${row.content || "입력된 내용이 없습니다."}`;
  $("decision-input").value = row.decision;
  $("decision-input").setCustomValidity("");
  $("reason-input").setCustomValidity("");
  $("reason-input").value = row.reason;
  $("non-disclosure-input").value = row.nonDisclosureReason;
  $("non-disclosure-input").setCustomValidity("");
  $("grades-input").value = (row.nonDisclosureGrades || []).join(",");
  $("grades-input").setCustomValidity("");
  $("restricted-part-input").value = row.restrictedPart || "";
  updateDisclosureFields(row);
  $("reviewed-input").checked = row.reviewed;
  $("reviewed-input").setCustomValidity("");
  $("ai-review").hidden = !state.aiEnabled || row.reviewed;
  $("ai-attachments").value = "";
  $("ai-complete").checked = false;
  renderReviewAttachments(row);
  renderAiResult(row, state.aiResults.get(sourceKey(row)));
  $("review-dialog").showModal();
});
function renderReviewAttachments(row) {
  const files = linkedFiles(row);
  const uploads = [...files, ...$("ai-attachments").files];
  const attachmentLines = files.length ? [`순번으로 연결한 첨부 ${files.length}개`, ...files.map(attachmentPath)] : [];
  const issue = linkIssue(row) || attachmentError(uploads);
  if (issue) attachmentLines.push(issue);
  else if (uploads.length) attachmentLines.push(`첨부 ${uploads.length}개 검토 대기 · 첨부 포함 AI 검토 시작 버튼을 눌러 주세요.`);
  else attachmentLines.push("첨부 없이 엑셀의 제목·생산연도·유형·내용으로 검토할 수 있습니다.");
  $("ai-linked-attachments").textContent = attachmentLines.join("\n");
  $("ai-complete-confirmation").hidden = !uploads.length;
  $("ai-run").textContent = uploads.length ? "첨부 포함 AI 검토 시작" : "엑셀 정보로 AI 검토";
}
const aiHoldMessages = {
  SKIPPED_REVIEWED: "검토가 완료된 기록물입니다.",
  SOURCE_INCOMPLETE: "기록물 전체 내용이 포함되어 있는지 확인한 후 검토를 실행해 주세요.",
  EXTRACTION_INCOMPLETE: "검토에 쓸 텍스트를 충분히 추출하지 못했습니다. 원문 또는 추출 엔진 상태를 확인해 주세요.",
  INPUT_TOO_LARGE: "기록물 전체가 AI 입력 한도를 넘었습니다. 담당자가 원문을 검토해 주세요.",
  MODEL_UNAVAILABLE: "로컬 Qwen 서버에 연결하지 못했습니다.",
  INVALID_MODEL_RESPONSE: "AI 추천의 응답 형식이나 근거를 검증할 수 없습니다.",
  CONFLICTING_FINDINGS: "일부 구간에 제한 근거나 추가 확인 사항이 있어 전체 공개 추천을 보류했습니다."
};
$("ai-attachments").addEventListener("change", () => {
  $("ai-complete").checked = false;
  const row = state.records.find(item => sourceKey(item) === state.editing);
  if (row) renderReviewAttachments(row);
  state.aiResults.delete(state.editing);
  if (row) renderAiResult(row);
  render();
});
function citationText({ location }, files) {
  if (location.row) {
    const sheet = sourceLabel({ sourceSheet: location.sheet }) || "엑셀";
    return `${sheet} ${location.row}행 · ${location.column}`;
  }
  const file = files.get(location.attachmentId) || location.attachmentId;
  if (location.startMillis !== undefined) {
    return `${file} · ${(location.startMillis / 1000).toFixed(2)}초~${(location.endMillis / 1000).toFixed(2)}초`;
  }
  const box = location.left === undefined ? "" : ` · 좌표 (${location.left}, ${location.top})–(${location.right}, ${location.bottom})`;
  return `${file} · ${location.page}페이지${box}`;
}
async function requestAiReview(row, uploads) {
  const data = new FormData();
  data.append("record", new Blob([JSON.stringify(row)], { type: "application/json" }), "record.json");
  data.append("completeRecord", "true");
  uploads.forEach((file, index) => {
    const extension = file.name.slice(file.name.lastIndexOf(".")).toLowerCase();
    data.append("attachments", file, `attachment-${index + 1}${extension}`);
  });
  const result = await (await api("/api/records/ai/review", { method: "POST", body: data })).json();
  const files = new Map(uploads.map((file, index) => [`attachment-${index + 1}`, attachmentPath(file)]));
  const entry = { result, files, originalStatus: row.originalStatus };
  state.aiResults.set(sourceKey(row), entry);
  return entry;
}
function renderAiResult(row, entry) {
  const change = recordChange(row, entry?.result);
  const decision = displayedDecision(row, entry?.result);
  const heading = !state.aiEnabled ? (change ? `변경 제안 ${change} · 현재 ${row.decision}` : `자동분류 추천 ${row.recommendation}`)
    : row.reviewed ? `담당자 확정 ${decision}`
    : decision ? `AI 추천 ${decision}` : entry ? "AI 검토 보류" : "AI 검토 대기";
  $("review-recommendation").textContent = `${heading} · ${sourceLabel(row) || "엑셀"} ${row.sourceRow}행`;
  $("ai-summary").hidden = !entry;
  $("ai-fill").hidden = true;
  if (!entry) return;
  const { result, files, originalStatus } = entry;
  if (result.status !== "READY") {
    $("ai-summary").textContent = aiHoldMessages[result.status] || "AI 검토를 보류했습니다.";
    return;
  }
  const proposal = result.proposal;
  const proposed = aiRecommendations[proposal.recommendation];
  const proposalChange = disclosureChange(originalStatus, proposed);
  const { attachmentCount, evidenceSegments, reviewedParts } = result.coverage;
  const scope = attachmentCount ? `엑셀 입력 정보와 첨부 ${attachmentCount}개` : "엑셀 입력 정보";
  const lines = [proposalChange ? `AI 변경 제안 · ${proposalChange}` : `AI 추천 · ${proposed}`, proposal.rationale,
    `적용 법령 · 정보공개법 제9조 (${result.policyVersion})`,
    `검토 범위 · ${scope}, 근거 구간 ${evidenceSegments}개, 분석 ${reviewedParts}회분`];
  if (proposal.criterionNumbers.length) lines.push(`관련 조항 · ${proposal.criterionNumbers.map(n => `제${n}호`).join(", ")}`);
  lines.push(...result.citations.map(citation => citationText(citation, files)));
  $("ai-summary").textContent = lines.join("\n");
  $("ai-fill").hidden = proposal.recommendation === "NEEDS_REVIEW";
}
$("ai-run").addEventListener("click", () => task(async () => {
  const row = state.records.find(item => sourceKey(item) === state.editing);
  if (!state.aiEnabled || !row || row.reviewed) return;
  const uploads = [...linkedFiles(row), ...$("ai-attachments").files];
  const summary = $("ai-summary");
  summary.hidden = false;
  $("ai-fill").hidden = true;
  const issue = linkIssue(row) || attachmentError(uploads);
  if (issue || (uploads.length && !$("ai-complete").checked)) {
    summary.textContent = issue || aiHoldMessages.SOURCE_INCOMPLETE;
    return;
  }
  state.aiResults.delete(sourceKey(row));
  summary.textContent = uploads.length ? "로컬 Qwen이 엑셀 입력 정보와 첨부를 등록 법령과 대조하고 있습니다."
    : "로컬 Qwen이 엑셀 입력 정보를 등록 법령과 대조하고 있습니다.";
  try {
    renderAiResult(row, await requestAiReview(row, uploads));
  } catch (error) {
    summary.textContent = error.message;
  }
  render();
}));
function batchTargets() {
  if (!state.aiEnabled) return [];
  return state.records.filter(row => !row.reviewed && !linkIssue(row)
    && state.aiResults.get(sourceKey(row))?.result.status !== "READY");
}
$("ai-batch").addEventListener("click", () => task(async () => {
  const targets = batchTargets();
  if (!targets.length) return;
  const withAttachments = targets.filter(row => linkedFiles(row).length).length;
  const attachmentCheck = withAttachments ? ` 첨부가 있는 ${withAttachments}건은 검토에 필요한 원문이 모두 연결됐는지 확인해 주세요.` : "";
  if (!confirm(`${targets.length}건의 엑셀 입력 정보와 연결된 첨부로 일괄 AI 검토를 시작할까요?${attachmentCheck}`)) return;
  state.batchRunning = true;
  state.stopBatch = false;
  renderButtons();
  let processed = 0;
  let recommendations = 0;
  let stoppedReason = "";
  const progress = $("ai-batch-progress");
  try {
    for (const row of targets) {
      if (state.stopBatch) break;
      progress.textContent = `${processed + 1}/${targets.length}건 검토 중 · ${sourceLabel(row)} ${attachmentSequencePath(row)}`;
      const { result } = await requestAiReview(row, linkedFiles(row));
      processed++;
      if (result.status === "READY" && result.proposal.recommendation !== "NEEDS_REVIEW") recommendations++;
      render();
      if (result.status === "MODEL_UNAVAILABLE") {
        stoppedReason = aiHoldMessages.MODEL_UNAVAILABLE;
        break;
      }
    }
  } catch (error) {
    stoppedReason = error.message;
  } finally {
    const summary = `처리 ${processed}/${targets.length}건 · 추천 ${recommendations}건 · 검토 보류 ${processed - recommendations}건`;
    progress.textContent = stoppedReason ? `${summary} · ${stoppedReason}` : summary;
    state.batchRunning = false;
    state.stopBatch = false;
    renderButtons();
  }
}));
$("ai-batch-stop").addEventListener("click", () => {
  state.stopBatch = true;
  renderButtons();
});
function acceptedAiProposal(row) {
  const result = state.aiResults.get(sourceKey(row))?.result;
  if (result?.status !== "READY") return null;
  return disclosureDecisions.has(aiRecommendations[result.proposal.recommendation]) ? result.proposal : null;
}
function aiDecisionFields(row, proposal) {
  const decision = aiRecommendations[proposal.recommendation];
  const change = disclosureChange(row.originalStatus, decision);
  const reason = change ? `AI 공개구분 변경 제안: ${change}\n${proposal.rationale}` : proposal.rationale;
  return {
    decision,
    reason: reason.slice(0, $("reason-input").maxLength),
    nonDisclosureReason: decision === "공개" ? "" : proposal.rationale,
    nonDisclosureGrades: row.sourceSheet && decision !== "공개" ? [...proposal.criterionNumbers] : [],
    restrictedPart: row.sourceSheet && decision === "부분공개" ? row.restrictedPart || "" : ""
  };
}
$("ai-bulk-complete").addEventListener("click", () => {
  if (!state.aiEnabled || state.busy) return;
  const targets = [];
  for (const row of state.records) {
    if (row.reviewed) continue;
    const proposal = acceptedAiProposal(row);
    if (proposal) targets.push({ row, proposal });
  }
  if (!targets.length) return;
  const count = targets.length.toLocaleString();
  if (!confirm(`현재 불러온 전체 목록에서 AI 추천이 나온 미검토 ${count}건을 검토완료로 확정할까요?`)) return;
  for (const { row, proposal } of targets) {
    Object.assign(row, aiDecisionFields(row, proposal), { reviewed: true });
    state.editedRows.add(sourceKey(row));
  }
  state.dirty = true;
  render();
  notify(`AI 추천 ${count}건을 검토완료로 적용했습니다. 결과 다운로드로 엑셀에 저장해 주세요.`);
});
$("ai-fill").addEventListener("click", () => {
  const row = state.records.find(item => sourceKey(item) === state.editing);
  if (state.busy || !row || row.reviewed) return;
  const proposal = acceptedAiProposal(row);
  if (!proposal) return;
  const values = aiDecisionFields(row, proposal);
  $("decision-input").value = values.decision;
  $("decision-input").dispatchEvent(new Event("change"));
  $("reason-input").value = values.reason;
  $("reason-input").setCustomValidity("");
  $("non-disclosure-input").value = values.nonDisclosureReason;
  $("grades-input").value = values.nonDisclosureGrades.join(",");
  $("reviewed-input").checked = false;
});
function updateDisclosureFields(row) {
  const decision = $("decision-input").value;
  const useGrades = Boolean(row?.sourceSheet) && ["비공개", "부분공개"].includes(decision);
  $("grades-label").hidden = $("grades-input").hidden = !useGrades;
  $("grades-input").disabled = !useGrades;
  const useRestrictedPart = Boolean(row?.sourceSheet) && decision === "부분공개";
  $("restricted-part-label").hidden = $("restricted-part-input").hidden = !useRestrictedPart;
  $("restricted-part-input").disabled = !useRestrictedPart;
}
$("review-form").addEventListener("submit", event => {
  event.preventDefault();
  const row = state.records.find(item => sourceKey(item) === state.editing);
  if (state.busy || !canReview(row)) return;
  const decision = $("decision-input").value;
  const needsCompletion = state.aiEnabled || disclosureChange(row.originalStatus, row.recommendation)
    || disclosureChange(row.originalStatus, decision);
  if (needsCompletion && decision !== "검토필요" && !$("reviewed-input").checked) {
    $("reviewed-input").setCustomValidity("공개구분을 확정하려면 원문을 확인하고 검토 완료에 체크해 주세요.");
    $("reviewed-input").reportValidity();
    return;
  }
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
  const grades = $("grades-input").disabled ? "" : $("grades-input").value.trim();
  if (grades && !/^[1-8](\s*,\s*[1-8])*$/.test(grades)) {
    $("grades-input").setCustomValidity("비공개호수는 1부터 8 사이의 숫자를 쉼표로 구분해 입력해 주세요.");
    $("grades-input").reportValidity();
    return;
  }
  row.decision = decision;
  row.reason = $("reason-input").value.trim();
  row.nonDisclosureReason = row.decision === "공개" ? "" : $("non-disclosure-input").value.trim();
  row.nonDisclosureGrades = grades ? [...new Set(grades.split(",").map(Number))] : [];
  row.restrictedPart = $("restricted-part-input").disabled ? "" : $("restricted-part-input").value.trim();
  row.reviewed = $("reviewed-input").checked;
  state.editedRows.add(sourceKey(row));
  state.dirty = true;
  $("review-dialog").close();
  render();
  notify("검토 결과를 적용했습니다. 결과 다운로드로 파일에 보관해 주세요.");
});
$("decision-input").addEventListener("change", () => {
  $("decision-input").setCustomValidity("");
  $("reviewed-input").setCustomValidity("");
  $("non-disclosure-input").setCustomValidity("");
  $("grades-input").setCustomValidity("");
  updateDisclosureFields(state.records.find(item => sourceKey(item) === state.editing));
});
$("grades-input").addEventListener("input", () => $("grades-input").setCustomValidity(""));
$("non-disclosure-input").addEventListener("input", () => $("non-disclosure-input").setCustomValidity(""));
$("reviewed-input").addEventListener("change", () => {
  $("reviewed-input").setCustomValidity("");
  $("decision-input").setCustomValidity("");
});
$("reason-input").addEventListener("input", () => $("reason-input").setCustomValidity(""));
$("guide-nav").addEventListener("click", () => $("guide-dialog").showModal());
document.querySelectorAll("[data-close]").forEach(button => button.addEventListener("click", () => $(button.dataset.close).close()));
window.addEventListener("beforeunload", event => { if (state.dirty) { event.preventDefault(); event.returnValue = ""; } });
async function initialize() {
  await task(async () => {
    state.aiEnabled = (await (await api("/api/records/ai/status")).json()).enabled;
    if (state.aiEnabled) {
      $("classify-button").hidden = true;
      $("attachment-picker").hidden = false;
      $("batch-controls").hidden = false;
      $("ai-bulk-complete").hidden = false;
      $("classification-stats").setAttribute("aria-label", "AI 추천 및 확정 집계");
      $("decision-heading").textContent = "AI 추천·확정";
      $("public-description").textContent = "AI 추천 또는 담당자 확정";
      $("partial-description").textContent = "AI 추천 또는 담당자 확정";
      $("private-description").textContent = "AI 추천 또는 담당자 확정";
      $("pending-description").textContent = "AI가 추가 검토를 요청한 건";
      $("ai-attachments").accept = attachmentAccept;
      $("review-guide").textContent = "각 행의 검토에서 Qwen 추천을 확인하고 최종 공개구분을 저장하세요.";
      $("workflow-review").lastChild.textContent = " 기록물 전체 검토";
      $("review-note").textContent = "Qwen은 엑셀 입력 정보와 연결된 첨부를 등록 법령 제9조와 대조해 공개구분을 추천합니다. 담당자가 검토 완료로 저장한 결과를 엑셀에 반영합니다.";
    }
    $("connection").textContent = "로컬 서버 연결됨";
    $("connection-dot").classList.add("online");
  });
  if (!$("connection-dot").classList.contains("online")) $("connection").textContent = "서버 연결 확인 필요";
  render();
}
initialize();
