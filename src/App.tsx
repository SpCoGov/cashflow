import { getCurrentWebview } from "@tauri-apps/api/webview";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { open as openDialog, save as saveDialog } from "@tauri-apps/plugin-dialog";
import { useEffect, useRef, useState } from "react";
import { AnalysisView } from "./AnalysisView";
import { api, ensureTaxonomy, errorText, type Analysis, type Category, type Entry } from "./api";
import { applyRename, CategoryEditor } from "./CategoryEditor";
import { DataTable, sortRows, useSort, type Column } from "./DataTable";
import { useDialogs } from "./dialogs";
import { baseName, daysInMonth, fmtDateTime, fmtYuan, stripExt, ymText } from "./format";
import { entryKey, ImportPreview } from "./ImportPreview";
import { EditRecordDialog, QuickAddDialog } from "./RecordEditor";
import { lastRulesPath, rememberRulesPath, RulesEditor, YAML_FILTER } from "./RulesEditor";

type Row = Entry & { id: number };
type Doc = { year: number; month: number; rows: Row[]; taxonomy: Category[]; path: string | null; dirty: boolean };
type Dialog =
  | { kind: "edit"; row?: Row }
  | { kind: "quick" }
  | { kind: "category" }
  | { kind: "import"; entries: Entry[] }
  | { kind: "rules" }
  | { kind: "analysis"; title: string; analysis: Analysis; exportName: string }
  | null;

const LEDGER_FILTERS = [
  { name: "Cashflow Ledger (*.cflg)", extensions: ["cflg"] },
  { name: "所有文件 (*.*)", extensions: ["*"] },
];
/** .bin 是旧版扩展名，格式相同 */
const isLedgerFile = (p: string) => /\.(cflg|bin)$/i.test(p);

let nextId = 1;
const withIds = (entries: Entry[]): Row[] => entries.map((e) => ({ ...e, id: nextId++ }));

const COLUMNS: Column<Row>[] = [
  { key: "ts", title: "日期时间", value: (r) => r.ts, render: (r) => fmtDateTime(r.ts), width: 170 },
  {
    key: "amount",
    title: "金额",
    align: "right",
    width: 110,
    value: (r) => r.amount,
    render: (r) => <span className={r.amount > 0 ? "pos" : r.amount < 0 ? "neg" : undefined}>{fmtYuan(r.amount)}</span>,
  },
  { key: "category", title: "分类", value: (r) => r.category, width: 120 },
  { key: "sub", title: "子类", value: (r) => r.sub, width: 120 },
  { key: "note", title: "备注", value: (r) => r.note },
];

function newDoc(): Doc {
  const now = new Date();
  return { year: now.getFullYear(), month: now.getMonth() + 1, rows: [], taxonomy: [], path: null, dirty: false };
}

export function App() {
  const dialogs = useDialogs();
  const [doc, setDoc] = useState<Doc>(newDoc);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useSort({ key: "ts", dir: 1 });
  const [lastQuickDay, setLastQuickDay] = useState<number | null>(null);

  const change = (f: (d: Doc) => Partial<Doc>) => setDoc((d) => ({ ...d, ...f(d), dirty: true }));
  const addRows = (entries: Entry[]) =>
    change((d) => ({
      rows: [...d.rows, ...withIds(entries)],
      taxonomy: entries.reduce((t, e) => ensureTaxonomy(t, e.category, e.sub), d.taxonomy),
    }));

  const save = async (): Promise<boolean> => {
    const d = doc;
    let path = d.path;
    if (!path) {
      const chosen = await saveDialog({ defaultPath: `账本-${ymText(d.year, d.month)}.cflg`, filters: [LEDGER_FILTERS[0]] });
      if (!chosen) return false;
      path = /\.cflg$/i.test(chosen) ? chosen : `${chosen}.cflg`;
    }
    try {
      const taxonomy = await api.saveLedger(path, { year: d.year, month: d.month, entries: d.rows, taxonomy: d.taxonomy });
      const saved = path;
      // 保存期间若又有修改，保留脏标记
      setDoc((cur) => (cur === d ? { ...d, path: saved, taxonomy, dirty: false } : { ...cur, path: saved }));
      return true;
    } catch (e) {
      await dialogs.alert(`保存失败：${errorText(e)}`);
      return false;
    }
  };

  /** 有未保存更改时询问；返回 true 表示可以继续（已保存或放弃更改）。 */
  const confirmDiscard = async (): Promise<boolean> => {
    if (!doc.dirty) return true;
    const i = await dialogs.choose("未保存的更改", "当前账本有未保存的更改，是否先保存？", ["保存", "不保存", "取消"]);
    return i === 0 ? save() : i === 1;
  };

  const loadFile = async (path: string) => {
    try {
      const l = await api.openLedger(path);
      setDoc({ year: l.year, month: l.month, rows: withIds(l.entries), taxonomy: l.taxonomy, path, dirty: false });
      setSelected(null);
    } catch (e) {
      dialogs.alert(`读取失败：${errorText(e)}`);
    }
  };

  // 长期存在的监听器（关闭、拖放、快捷键）通过它拿到最新的状态和函数
  const latest = useRef({ doc, dialog, save, confirmDiscard, loadFile });
  useEffect(() => {
    latest.current = { doc, dialog, save, confirmDiscard, loadFile };
  });

  useEffect(() => {
    const name = doc.path ? baseName(doc.path) : "未命名.cflg";
    getCurrentWindow()
      .setTitle(`${doc.dirty ? "* " : ""}${name}  -  ${ymText(doc.year, doc.month)}`)
      .catch(() => {});
  }, [doc.path, doc.dirty, doc.year, doc.month]);

  useEffect(() => {
    const win = getCurrentWindow();
    const unClose = win.onCloseRequested(async (event) => {
      if (!latest.current.doc.dirty) return;
      event.preventDefault();
      if (await latest.current.confirmDiscard()) await win.destroy();
    });
    const unDrop = getCurrentWebview().onDragDropEvent(async (event) => {
      if (event.payload.type !== "drop") return;
      const path = event.payload.paths.find(isLedgerFile);
      if (!path) {
        if (event.payload.paths.length) dialogs.alert("不支持的文件类型，只支持 *.cflg / *.bin");
        return;
      }
      if (await latest.current.confirmDiscard()) latest.current.loadFile(path);
    });
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        if (!latest.current.dialog) latest.current.save();
      }
    };
    window.addEventListener("keydown", onKey);
    api.startupPath().then((p) => {
      if (!p) return;
      if (isLedgerFile(p)) latest.current.loadFile(p);
      else dialogs.alert(`不支持的文件类型: ${p}`);
    });
    return () => {
      unClose.then((f) => f());
      unDrop.then((f) => f());
      window.removeEventListener("keydown", onKey);
    };
  }, [dialogs]);

  const q = query.trim().toLowerCase();
  const filtered = q
    ? doc.rows.filter((r) => [fmtDateTime(r.ts), fmtYuan(r.amount), r.category, r.sub, r.note].some((s) => s.toLowerCase().includes(q)))
    : doc.rows;
  const selectedRow = doc.rows.find((r) => r.id === selected);

  const deleteSelected = () => {
    if (!selectedRow) return;
    const order = sortRows(filtered, COLUMNS, sort);
    const i = order.findIndex((r) => r.id === selectedRow.id);
    const next = order[i + 1] ?? order[i - 1];
    change((d) => ({ rows: d.rows.filter((r) => r.id !== selectedRow.id) }));
    setSelected(next?.id ?? null);
  };

  const openFile = async () => {
    const p = await openDialog({ filters: LEDGER_FILTERS });
    if (typeof p === "string" && (await confirmDiscard())) loadFile(p);
  };

  const setYm = (year: number, month: number) => {
    change(() => ({ year, month }));
    if (lastQuickDay) setLastQuickDay(Math.min(lastQuickDay, daysInMonth(year, month)));
  };

  const importBill = async () => {
    const bill = await openDialog({
      title: "选择微信支付账单",
      filters: [
        { name: "微信账单 (*.xlsx, *.csv)", extensions: ["xlsx", "csv", "txt"] },
        { name: "所有文件 (*.*)", extensions: ["*"] },
      ],
    });
    if (typeof bill !== "string") return;
    const rules = await openDialog({ title: "选择导入规则（可选，取消则不使用规则）", defaultPath: lastRulesPath(), filters: YAML_FILTER });
    const rulesPath = typeof rules === "string" ? rules : null;
    if (rulesPath) rememberRulesPath(rulesPath);
    const lastTs = doc.rows.length ? doc.rows.reduce((m, r) => Math.max(m, r.ts), -Infinity) : null;
    try {
      const entries = await api.importBill(bill, rulesPath, lastTs);
      if (!entries.length) return dialogs.alert("没有可导入的记录。");
      setDialog({ kind: "import", entries });
    } catch (e) {
      dialogs.alert(`导入失败：${errorText(e)}`);
    }
  };

  const showAnalysis = async (name: string, entries: Entry[]) => {
    const analysis = await api.analyze(entries);
    setDialog({ kind: "analysis", title: `分析 - ${name}`, analysis, exportName: `${stripExt(name)}-分析.xlsx` });
  };
  const analyzeFile = async () => {
    const p = await openDialog({ filters: LEDGER_FILTERS });
    if (typeof p !== "string") return;
    try {
      await showAnalysis(baseName(p), (await api.openLedger(p)).entries);
    } catch (e) {
      dialogs.alert(`分析失败：${errorText(e)}`);
    }
  };

  const thisYear = new Date().getFullYear();
  const years = Array.from({ length: 21 }, (_, i) => thisYear - 10 + i);
  if (!years.includes(doc.year)) years.push(doc.year), years.sort((a, b) => a - b);
  const close = () => setDialog(null);

  return (
    <div className="app">
      <header className="main-toolbar">
        <div className="group">
          <button onClick={openFile}>打开…</button>
          <button onClick={() => save()} title="Ctrl+S">
            保存
          </button>
        </div>
        <div className="group">
          <button onClick={() => setDialog({ kind: "edit" })}>添加</button>
          <button onClick={() => setDialog({ kind: "quick" })}>快速添加</button>
          <button disabled={!selectedRow} onClick={() => selectedRow && setDialog({ kind: "edit", row: selectedRow })}>
            编辑
          </button>
          <button disabled={!selectedRow} onClick={deleteSelected}>
            删除
          </button>
          <button onClick={() => setDialog({ kind: "category" })}>编辑分类</button>
        </div>
        <div className="group">
          <button onClick={importBill}>导入微信账单…</button>
          <button onClick={() => setDialog({ kind: "rules" })}>解析规则</button>
        </div>
        <div className="group">
          <button
            onClick={() =>
              showAnalysis(doc.path ? baseName(doc.path) : `账本-${ymText(doc.year, doc.month)}`, doc.rows).catch((e) =>
                dialogs.alert(`分析失败：${errorText(e)}`),
              )
            }
          >
            分析
          </button>
          <button onClick={analyzeFile}>分析文件…</button>
        </div>
      </header>

      <div className="subbar">
        <label>
          年{" "}
          <select value={doc.year} onChange={(e) => setYm(Number(e.target.value), doc.month)}>
            {years.map((y) => (
              <option key={y}>{y}</option>
            ))}
          </select>
        </label>
        <label>
          月{" "}
          <select value={doc.month} onChange={(e) => setYm(doc.year, Number(e.target.value))}>
            {Array.from({ length: 12 }, (_, i) => (
              <option key={i + 1}>{i + 1}</option>
            ))}
          </select>
        </label>
        <input type="search" aria-label="搜索" placeholder="搜索时间、金额、分类、备注" value={query} onChange={(e) => setQuery(e.target.value)} />
        <span className="muted">{q ? `${filtered.length} / ${doc.rows.length} 条` : `${doc.rows.length} 条`}</span>
      </div>

      <main className="table-scroll">
        <DataTable
          columns={COLUMNS}
          rows={filtered}
          rowKey={(r) => r.id}
          sort={sort}
          onSort={setSort}
          selected={selected}
          onSelect={(r) => setSelected(r.id)}
          onActivate={(r) => setDialog({ kind: "edit", row: r })}
          empty={doc.rows.length ? "没有匹配的记录" : "打开或拖入 .cflg 账本，或点「添加」开始记账"}
        />
      </main>

      {dialog?.kind === "edit" && (
        <EditRecordDialog
          initial={dialog.row}
          taxonomy={doc.taxonomy}
          onCancel={close}
          onOk={(e) => {
            const id = dialog.row?.id;
            if (id === undefined) addRows([e]);
            else
              change((d) => ({
                rows: d.rows.map((r) => (r.id === id ? { ...e, id } : r)),
                taxonomy: ensureTaxonomy(d.taxonomy, e.category, e.sub),
              }));
            close();
          }}
        />
      )}
      {dialog?.kind === "quick" && (
        <QuickAddDialog
          year={doc.year}
          month={doc.month}
          initialDay={lastQuickDay ?? Math.min(new Date().getDate(), daysInMonth(doc.year, doc.month))}
          taxonomy={doc.taxonomy}
          onAdd={(e) => addRows([e])}
          onClose={(day) => {
            setLastQuickDay(day);
            close();
          }}
        />
      )}
      {dialog?.kind === "category" && (
        <CategoryEditor
          taxonomy={doc.taxonomy}
          used={doc.rows}
          onCancel={close}
          onOk={(taxonomy, ops) => {
            change((d) => ({ taxonomy, rows: ops.reduce((rows, op) => applyRename(rows, op), d.rows) }));
            close();
          }}
        />
      )}
      {dialog?.kind === "import" && (
        <ImportPreview
          entries={dialog.entries}
          existing={new Set(doc.rows.map(entryKey))}
          taxonomy={doc.taxonomy}
          onCancel={close}
          onOk={(entries) => {
            addRows(entries);
            close();
            dialogs.alert(`导入完成：共 ${entries.length} 条。`);
          }}
        />
      )}
      {dialog?.kind === "rules" && <RulesEditor onClose={close} />}
      {dialog?.kind === "analysis" && (
        <AnalysisView title={dialog.title} analysis={dialog.analysis} exportName={dialog.exportName} onClose={close} />
      )}
    </div>
  );
}
