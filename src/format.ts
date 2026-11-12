const pad = (n: number, w = 2) => String(n).padStart(w, "0");

/** yyyy-MM-dd HH:mm:ss（本地时区） */
export function fmtDateTime(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** 接受 yyyy-MM-dd HH:mm[:ss]，或 10 位秒 / 11~13 位毫秒时间戳。 */
export function parseDateTime(text: string): number | null {
  const t = text.trim();
  if (/^\d{10}$/.test(t)) return Number(t) * 1000;
  if (/^\d{11,13}$/.test(t)) return Number(t);
  const m = /^(\d{4})[-/](\d{1,2})[-/](\d{1,2})[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?$/.exec(t);
  if (!m) return null;
  const [y, mo, d, h, mi, s] = m.slice(1).map((x) => Number(x ?? 0));
  const date = new Date(y, mo - 1, d, h, mi, s);
  const exact = date.getMonth() === mo - 1 && date.getDate() === d && h < 24 && mi < 60 && s < 60;
  return exact ? date.getTime() : null;
}

function splitCents(cents: number): [string, string, string] {
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(cents);
  return [sign, String(Math.floor(abs / 100)), pad(abs % 100)];
}

/** 0.00 形式 */
export function fmtYuan(cents: number): string {
  const [sign, int, frac] = splitCents(cents);
  return `${sign}${int}.${frac}`;
}

/** #,##0.00 形式 */
export function fmtYuanGrouped(cents: number): string {
  const [sign, int, frac] = splitCents(cents);
  return `${sign}${int.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}.${frac}`;
}

export const fmtPct = (v: number) => `${(v * 100).toFixed(2)}%`;

/** 元 → 分，超过两位小数按四舍五入（远离零），无法解析时返回 null。 */
export function yuanToCents(text: string): number | null {
  const m = /^([+-])?(\d*)(?:\.(\d*))?$/.exec(text.trim().replace(/,/g, ""));
  if (!m || (!m[2] && !m[3])) return null;
  const frac = (m[3] ?? "").padEnd(3, "0");
  let cents = Number(m[2] || "0") * 100 + Number(frac.slice(0, 2));
  if (Number(frac[2]) >= 5) cents += 1;
  if (!Number.isSafeInteger(cents)) return null;
  return m[1] === "-" ? -cents : cents;
}

/** 快速添加：不带符号视为支出，+ 为收入。 */
export function parseQuickAmount(text: string): number | null {
  const t = text.trim();
  const cents = yuanToCents(t.replace(/^[+-]/, ""));
  if (cents === null) return null;
  return t.startsWith("+") ? cents : -cents;
}

export const daysInMonth = (year: number, month: number) => new Date(year, month, 0).getDate();
export const ymText = (year: number, month: number) => `${year}-${pad(month)}`;

export function baseName(path: string): string {
  return path.split(/[\\/]/).pop() ?? path;
}

export function stripExt(name: string): string {
  const i = name.lastIndexOf(".");
  return i > 0 ? name.slice(0, i) : name;
}
