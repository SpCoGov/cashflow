import { save as saveDialog } from "@tauri-apps/plugin-dialog";
import { useState } from "react";
import { api, errorText, type Analysis, type Stat } from "./api";
import { DataTable, useSort, type Column } from "./DataTable";
import { Modal, useDialogs } from "./dialogs";
import { fmtPct, fmtYuanGrouped } from "./format";

const PALETTE = ["#4e79a7", "#f28e2b", "#e15759", "#76b7b2", "#59a14f", "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac"];

type Slice = { label: string; value: number };

function PieChart({ title, slices }: { title: string; slices: Slice[] }) {
  const total = slices.reduce((s, x) => s + x.value, 0);
  let acc = 0;
  const stops = slices.map((s, i) => {
    const from = (acc / total) * 100;
    acc += s.value;
    return `${PALETTE[i % PALETTE.length]} ${from}% ${(acc / total) * 100}%`;
  });
  return (
    <figure className="chart">
      <figcaption>{title}</figcaption>
      {total > 0 && (
        <div className="pie-wrap">
          <div className="pie" role="img" aria-label={title} style={{ background: `conic-gradient(${stops.join(",")})` }} />
          <ul className="legend">
            {slices.map((s, i) => (
              <li key={s.label}>
                <i style={{ background: PALETTE[i % PALETTE.length] }} />
                <span>{s.label}</span>
                <b>{fmtPct(s.value / total)}</b>
              </li>
            ))}
          </ul>
        </div>
      )}
    </figure>
  );
}

function BarChart({ title, bars }: { title: string; bars: Slice[] }) {
  const max = Math.max(1, ...bars.map((b) => b.value));
  return (
    <figure className="chart">
      <figcaption>{title}</figcaption>
      <ul className="bars">
        {bars.map((b) => (
          <li key={b.label}>
            <span className="bar-label">{b.label}</span>
            <span className="bar-track">
              <span className="bar" style={{ width: `${(b.value / max) * 100}%` }} />
            </span>
            <span className="bar-value">{fmtYuanGrouped(b.value)}</span>
          </li>
        ))}
      </ul>
    </figure>
  );
}

const money = (key: keyof Stat, title: string): Column<Stat> => ({
  key,
  title,
  align: "right",
  value: (s) => s[key] as number,
  render: (s) => fmtYuanGrouped(s[key] as number),
});
const countCol: Column<Stat> = { key: "count", title: "笔数", align: "right", value: (s) => s.count, width: 64 };
const shareCol = (title: string): Column<Stat> => ({
  key: "expenseShare",
  title,
  align: "right",
  value: (s) => s.expenseShare,
  render: (s) => fmtPct(s.expenseShare),
});
const catCol: Column<Stat> = { key: "category", title: "类别", value: (s) => s.category };
const subCol: Column<Stat> = { key: "sub", title: "子类别", value: (s) => s.sub ?? "" };
const avgMax = [money("avgExpense", "平均支出(元)"), money("maxExpense", "最大支出(元)")];
const CAT_COLUMNS = [catCol, countCol, money("income", "收入(元)"), money("expense", "支出(元)"), money("net", "净额(元)"), shareCol("支出占比(全局)"), ...avgMax];
const SUB_COLUMNS = [catCol, subCol, countCol, money("income", "收入(元)"), money("expense", "支出(元)"), money("net", "净额(元)"), shareCol("在本类别占比"), ...avgMax];
const SHARE_COLUMNS = [subCol, countCol, money("expense", "支出(元)"), shareCol("在本类别占比"), ...avgMax];

type Tab = "overview" | "details" | "share";

type Props = { title: string; analysis: Analysis; exportName: string; onClose: () => void };

export function AnalysisView({ title, analysis: a, exportName, onClose }: Props) {
  const dialogs = useDialogs();
  const [tab, setTab] = useState<Tab>("overview");
  const first = a.categories[0]?.category ?? null;
  const [detailCat, setDetailCat] = useState<string | null>(first);
  const [shareCat, setShareCat] = useState<string | null>(first);
  const [catSort, setCatSort] = useSort();
  const [subSort, setSubSort] = useSort();
  const [shareSort, setShareSort] = useSort();

  const byExpense = [...a.categories].filter((s) => s.expense !== 0).sort((x, y) => x.expense - y.expense);
  const bars = byExpense.slice(0, 10).map((s) => ({ label: s.category, value: -s.expense }));
  const others = byExpense.slice(10).reduce((sum, s) => sum - s.expense, 0);
  if (others > 0) bars.push({ label: "其它", value: others });
  const shareSubs = a.subs.filter((s) => s.category === shareCat);
  const sharePie = shareSubs.filter((s) => s.expense !== 0).map((s) => ({ label: s.sub ?? "", value: -s.expense }));

  const exportXlsx = async () => {
    const p = await saveDialog({ defaultPath: exportName, filters: [{ name: "Excel 工作簿 (*.xlsx)", extensions: ["xlsx"] }] });
    if (!p) return;
    try {
      await api.exportAnalysis(/\.xlsx$/i.test(p) ? p : `${p}.xlsx`, a);
      dialogs.alert("导出完成");
    } catch (e) {
      dialogs.alert(`导出失败：${errorText(e)}`);
    }
  };

  const tabs: [Tab, string][] = [
    ["overview", "概览"],
    ["details", "明细"],
    ["share", "子类占比"],
  ];

  return (
    <Modal
      title={title}
      size="full"
      onClose={onClose}
      footer={
        <>
          <button onClick={exportXlsx}>导出为 Excel</button>
          <button className="primary" onClick={onClose}>
            关闭
          </button>
        </>
      }
    >
      <div className="tabs" role="tablist">
        {tabs.map(([k, label]) => (
          <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? "active" : undefined} onClick={() => setTab(k)}>
            {label}
          </button>
        ))}
      </div>

      {tab === "overview" && (
        <div className="tab-body">
          <dl className="totals">
            <dt>总收入（元）</dt>
            <dd className="pos">{fmtYuanGrouped(a.totalIncome)}</dd>
            <dt>总支出（元）</dt>
            <dd className="neg">{fmtYuanGrouped(-a.totalExpenseAbs)}</dd>
            <dt>净额（元）</dt>
            <dd>{fmtYuanGrouped(a.net)}</dd>
            <dt>记录数</dt>
            <dd>{a.count}</dd>
          </dl>
          <div className="charts">
            <PieChart
              title={byExpense.length ? "类别支出占比" : "类别支出占比（无支出数据）"}
              slices={a.categories.filter((s) => s.expense !== 0).map((s) => ({ label: s.category, value: -s.expense }))}
            />
            <BarChart title="Top 10 类别支出（元）" bars={bars} />
          </div>
        </div>
      )}

      {tab === "details" && (
        <div className="tab-body split">
          <div className="table-scroll">
            <DataTable
              columns={CAT_COLUMNS}
              rows={a.categories}
              rowKey={(s) => s.category}
              sort={catSort}
              onSort={setCatSort}
              selected={detailCat}
              onSelect={(s) => setDetailCat(s.category)}
              onActivate={(s) => {
                setShareCat(s.category);
                setTab("share");
              }}
            />
          </div>
          <div className="table-scroll">
            <DataTable
              columns={SUB_COLUMNS}
              rows={a.subs.filter((s) => detailCat === null || s.category === detailCat)}
              rowKey={(s) => `${s.category}\u0000${s.sub}`}
              sort={subSort}
              onSort={setSubSort}
            />
          </div>
          <p className="hint">双击类别查看子类占比</p>
        </div>
      )}

      {tab === "share" && (
        <div className="tab-body">
          <label className="field inline">
            <span>类别</span>
            <select value={shareCat ?? ""} onChange={(e) => setShareCat(e.target.value)}>
              {a.categories.map((s) => (
                <option key={s.category}>{s.category}</option>
              ))}
            </select>
          </label>
          <div className="split">
            <PieChart
              title={shareCat === null ? "子类占比（请选择类别）" : sharePie.length ? "子类占比（按类别支出）" : "子类占比（该类别无支出）"}
              slices={sharePie}
            />
            <div className="table-scroll">
              <DataTable columns={SHARE_COLUMNS} rows={shareSubs} rowKey={(s) => s.sub ?? ""} sort={shareSort} onSort={setShareSort} />
            </div>
          </div>
        </div>
      )}
    </Modal>
  );
}
