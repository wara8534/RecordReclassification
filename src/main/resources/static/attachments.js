export const attachmentAccept = ".pdf,.png,.jpg,.jpeg,.wav,.mp3,.m4a,.flac,.mp4,.mov,.mkv,.webm";
const allowedExtensions = new Set(attachmentAccept.split(","));
const MAX_FILE_SIZE = 25 * 1024 * 1024;
const MAX_RECORD_SIZE = 100 * 1024 * 1024;
const MAX_FILES = 20;
const storageName = value => value.normalize("NFC").toLowerCase();

export const sourceKey = row => `${row.sourceSheet || ""}:${row.sourceRow}`;
export const attachmentPath = file => file.webkitRelativePath || file.name;

function sequenceKey(value) {
  const digits = String(value ?? "").trim().replace(/^0+/, "");
  return /^[1-9][0-9]*$/.test(digits) ? digits : "";
}

export function attachmentSequencePath(row) {
  const numbers = row.sourceSheet === "ITEM"
    ? [sequenceKey(row.parentFolderSequence), sequenceKey(row.itemSequence)]
    : [sequenceKey(row.sourceSequence)];
  return numbers.every(Boolean) ? numbers.join("/") : "";
}

function isBatchTimestamp(value) {
  const parts = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})$/.exec(value);
  if (!parts) return false;
  const [, year, month, day, hour, minute, second] = parts;
  const iso = `${year}-${month}-${day}T${hour}:${minute}:${second}.000Z`;
  const date = new Date(iso);
  return Number.isFinite(date.getTime()) && date.toISOString() === iso;
}

export function attachmentRuns(filename, files) {
  if (!filename) return [];
  const workbookNames = new Set([storageName(filename), storageName(filename.replace(/\.(xlsx|xls)$/i, ""))]);
  const runs = new Map();
  for (const file of files) {
    const path = attachmentPath(file).normalize("NFC").split("/");
    const offset = storageName(path[0]) === "recordon" && workbookNames.has(storageName(path[1] || "")) ? 1 : 0;
    const [workbookFolder, timestamp, ...relative] = path.slice(offset);
    if (!workbookFolder || !workbookNames.has(storageName(workbookFolder)) || !isBatchTimestamp(timestamp) || !relative.length) continue;
    const label = `${workbookFolder}/${timestamp}`;
    if (!runs.has(label)) runs.set(label, { label, timestamp, files: [] });
    runs.get(label).files.push({ file, relative });
  }
  return [...runs.values()].sort((first, second) => second.timestamp.localeCompare(first.timestamp)
    || first.label.localeCompare(second.label, "ko"));
}

export function attachmentError(files) {
  const unsupported = files.find(file => !allowedExtensions.has(file.name.slice(file.name.lastIndexOf(".")).toLowerCase()));
  if (unsupported) return `지원하지 않는 파일 형식입니다 (${unsupported.name}).`;
  if (files.some(file => !file.size || file.size > MAX_FILE_SIZE)) return "빈 파일 또는 25MB를 넘는 첨부가 있습니다.";
  if (files.length > MAX_FILES || files.reduce((total, file) => total + file.size, 0) > MAX_RECORD_SIZE) {
    return "기록물당 첨부 20개 또는 총 100MB 한도를 넘었습니다.";
  }
  return "";
}

export function linkAttachments(records, run) {
  const byPath = new Map();
  for (const row of records) {
    const path = attachmentSequencePath(row);
    if (!path) continue;
    if (!byPath.has(path)) byPath.set(path, []);
    byPath.get(path).push(row);
  }

  const matches = new Map();
  const issues = new Map();
  const unmatched = [];
  for (const { file, relative } of run?.files || []) {
    const path = attachmentPath(file);
    const folders = relative.slice(0, -1);
    const numbers = folders.slice(0, 2).map(folder => sequenceKey(folder.split("_", 1)[0]));
    const sequence = numbers.length && numbers.every(Boolean) ? numbers.join("/") : "";
    const rows = byPath.get(sequence) || [];
    if (!sequence || folders.length > 2) {
      const reason = "철 순번 또는 철 순번/건 일련번호 폴더 바로 아래에 파일을 놓아 주세요.";
      for (const row of rows) issues.set(sourceKey(row), reason);
      unmatched.push({ path, reason });
      continue;
    }
    if (rows.length === 1) {
      const key = sourceKey(rows[0]);
      if (!matches.has(key)) matches.set(key, []);
      matches.get(key).push(file);
    } else if (rows.length > 1) {
      const message = `첨부 경로 ${sequence}에 해당하는 행이 여러 건입니다. 엑셀의 철 순번과 건 일련번호를 확인해 주세요.`;
      for (const row of rows) issues.set(sourceKey(row), message);
      unmatched.push({ path, reason: "순번 중복" });
    } else {
      unmatched.push({ path, reason: "일치하는 철 또는 건 없음" });
    }
  }
  for (const [key, linked] of matches) {
    linked.sort((first, second) => attachmentPath(first).localeCompare(attachmentPath(second), "ko", { numeric: true }));
    const error = attachmentError(linked);
    if (error && !issues.has(key)) issues.set(key, error);
  }
  return { matches, issues, unmatched };
}
