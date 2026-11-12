import { invoke } from "@tauri-apps/api/core";

/** 金额单位：分（正数收入，负数支出）；时间：epoch 毫秒。 */
export type Entry = { ts: number; amount: number; category: string; sub: string; note: string };
export type Category = { name: string; subs: string[] };
export type Ledger = { year: number; month: number; entries: Entry[]; taxonomy: Category[] };

export type TextMatch = { contains?: string | null; equals?: string | null; regex?: string | null; ignoreCase?: boolean | null };
/** 后端序列化时会省略空字段，读取后需补缺省值（见 RulesEditor 的 load）。 */
export type RuleDef = {
  name?: string | null;
  when: { payee?: TextMatch | null; item?: TextMatch | null; note?: TextMatch | null; amount?: string | null };
  then: {
    drop?: boolean | null;
    category?: string | null;
    sub?: string | null;
    noteReplace?: { regex?: string | null; with?: string | null } | null;
  };
};
export type RuleConfig = {
  version: number;
  defaults?: { category?: string; sub?: string; noteFallbackOrder?: string[]; onlyAppendFromLastDate?: boolean };
  rules: (Partial<Pick<RuleDef, "when" | "then">> & Omit<RuleDef, "when" | "then">)[];
};

export type Stat = {
  category: string;
  sub?: string | null;
  count: number;
  income: number;
  expense: number;
  net: number;
  expenseCount: number;
  maxExpense: number;
  avgExpense: number;
  expenseShare: number;
};
export type Analysis = {
  totalIncome: number;
  totalExpenseAbs: number;
  net: number;
  count: number;
  categories: Stat[];
  subs: Stat[];
};

export const api = {
  openLedger: (path: string) => invoke<Ledger>("open_ledger", { path }),
  saveLedger: (path: string, ledger: Ledger) => invoke<Category[]>("save_ledger", { path, ledger }),
  importBill: (path: string, rulesPath: string | null, lastTs: number | null) =>
    invoke<Entry[]>("import_bill", { path, rulesPath, lastTs }),
  loadRules: (path: string) => invoke<RuleConfig>("load_rules", { path }),
  saveRules: (path: string, config: RuleConfig) => invoke<void>("save_rules", { path, config }),
  analyze: (entries: Entry[]) => invoke<Analysis>("analyze", { entries }),
  exportAnalysis: (path: string, analysis: Analysis) => invoke<void>("export_analysis", { path, analysis }),
  startupPath: () => invoke<string | null>("startup_path"),
};

/** 把分类/子类并入分类树（不存在则追加），返回新数组。 */
export function ensureTaxonomy(tax: Category[], category: string, sub: string): Category[] {
  const c = category.trim();
  const s = sub.trim();
  if (!c || !s) return tax;
  const i = tax.findIndex((x) => x.name === c);
  if (i < 0) return [...tax, { name: c, subs: [s] }];
  if (tax[i].subs.includes(s)) return tax;
  return tax.map((x, j) => (j === i ? { ...x, subs: [...x.subs, s] } : x));
}

export const errorText = (e: unknown) => (typeof e === "string" ? e : e instanceof Error ? e.message : String(e));
