import { useState, type MouseEvent } from "react";
import type { Category, Entry } from "./api";
import { Modal, useDialogs } from "./dialogs";
import { fmtDateTime, fmtYuan, parseDateTime, yuanToCents } from "./format";

type PreviewRow = {
  key: number;
  use: boolean;
  /** 账本里已有同一时间、同一金额的记录 */
  dup: boolean;
  tsText: string;
  amountText: string;
  category: string;
  sub: string;
  note: string;
};

export const entryKey = (e: { ts: number; amount: number }) => `${e.ts}|${e.amount}`;

type Props = {
  entries: Entry[];
  existing: Set<string>;
  taxonomy: Category[];
  onOk: (entries: Entry[]) => void;
  onCancel: () => void;
};

export function ImportPreview({ entries, existing, taxonomy, onOk, onCancel }: Props) {
  const dialogs = useDialogs();
  const [rows, setRows] = useState<PreviewRow[]>(() =>
    entries.map((e, key) => {
      const dup = existing.has(entryKey(e));
      return {
        key,
        use: !dup,
        dup,
        tsText: fmtDateTime(e.ts),
        amountText: fmtYuan(e.amount),
        category: e.category,
        sub: e.sub,
        note: e.note,
      };
    }),
  );
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [anchor, setAnchor] = useState<number | null>(null);

  const update = (keys: Iterable<number>, patch: (r: PreviewRow) => Partial<PreviewRow>) => {
    const set = new Set(keys);
    setRows((rs) => rs.map((r) => (set.has(r.key) ? { ...r, ...patch(r) } : r)));
  };

  const select = (e: MouseEvent, idx: number) => {
    const key = rows[idx].key;
    if (e.shiftKey && anchor !== null) {
      const [a, b] = [Math.min(anchor, idx), Math.max(anchor, idx)];
      setSelected(new Set(rows.slice(a, b + 1).map((r) => r.key)));
      return;
    }
    setAnchor(idx);
    if (e.ctrlKey || e.metaKey) {
      const next = new Set(selected);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      setSelected(next);
    } else if (!selected.has(key) || selected.size > 1) {
      setSelected(new Set([key]));
    }
  };

  const bulkCategory = async () => {
    const v = await dialogs.prompt("批量设置【分类】", "对所选行设置分类：");
    if (v !== null) update(selected, () => ({ category: v, sub: "" }));
  };
  const bulkSub = async () => {
    const v = await dialogs.prompt("批量设置【子类】", "对所选行设置子类：");
    if (v !== null) update(selected, () => ({ sub: v }));
  };

  const using = rows.filter((r) => r.use);
  const incomplete = using.filter((r) => !r.category.trim() || !r.sub.trim()).length;
  const dups = rows.filter((r) => r.dup).length;

  const ok = async () => {
    if (incomplete) return dialogs.alert("存在未填写分类/子类的记录，请补全或取消勾选。");
    const out: Entry[] = [];
    for (const r of using) {
      const n = rows.indexOf(r) + 1;
      const ts = parseDateTime(r.tsText);
      if (ts === null) return dialogs.alert(`第 ${n} 行时间格式不正确，示例：2025-01-31 12:34:56`);
      const amount = yuanToCents(r.amountText);
      if (amount === null) return dialogs.alert(`第 ${n} 行金额格式不正确`);
      out.push({ ts, amount, category: r.category.trim(), sub: r.sub.trim(), note: r.note.trim() });
    }
    onOk(out);
  };

  const subList = (category: string) => {
    const i = taxonomy.findIndex((c) => c.name === category.trim());
    return i < 0 ? undefined : `dl-import-sub-${i}`;
  };
  const noSelection = selected.size === 0;

  return (
    <Modal
      title="导入预览"
      size="large"
      onClose={onCancel}
      footer={
        <>
          <span className="muted">
            共 {rows.length} 条；已勾选 {using.length} 条；待补全(分类/子类) {incomplete} 条
            {dups > 0 && `；疑似重复 ${dups} 条（默认不勾选）`}
          </span>
          <button onClick={onCancel}>取消</button>
          <button className="primary" onClick={ok}>
            导入
          </button>
        </>
      }
    >
      <div className="toolbar">
        <button
          onClick={() => {
            const all = rows.some((r) => !r.use);
            setRows(rows.map((r) => ({ ...r, use: all })));
          }}
        >
          全选/全不选
        </button>
        <button disabled={noSelection} onClick={bulkCategory}>
          批量设置分类
        </button>
        <button disabled={noSelection} onClick={bulkSub}>
          批量设置子类
        </button>
        <button disabled={noSelection} onClick={() => update(selected, () => ({ note: "" }))}>
          清空备注
        </button>
        <span className="muted">单击行选中，Ctrl/Shift 多选</span>
      </div>
      <datalist id="dl-import-cats">
        {taxonomy.map((c) => (
          <option key={c.name} value={c.name} />
        ))}
      </datalist>
      {taxonomy.map((c, i) => (
        <datalist key={c.name} id={`dl-import-sub-${i}`}>
          {c.subs.map((s) => (
            <option key={s} value={s} />
          ))}
        </datalist>
      ))}
      <div className="table-scroll">
        <table className="grid editable">
          <thead>
            <tr>
              <th style={{ width: 84 }}>导入</th>
              <th style={{ width: 170 }}>时间</th>
              <th style={{ width: 100 }} className="num">
                金额
              </th>
              <th style={{ width: 130 }}>分类</th>
              <th style={{ width: 130 }}>子类</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, idx) => {
              const missing = r.use && (!r.category.trim() || !r.sub.trim());
              const cls = [selected.has(r.key) && "selected", !r.use && "unused"].filter(Boolean).join(" ");
              return (
                <tr key={r.key} className={cls || undefined} onClick={(e) => select(e, idx)}>
                  <td>
                    <input
                      type="checkbox"
                      aria-label="导入此行"
                      checked={r.use}
                      onChange={(e) => update([r.key], () => ({ use: e.target.checked }))}
                    />
                    {r.dup && <span className="badge" title="账本中已有同一时间、同一金额的记录">已存在</span>}
                  </td>
                  <td>
                    <input value={r.tsText} onChange={(e) => update([r.key], () => ({ tsText: e.target.value }))} />
                  </td>
                  <td>
                    <input
                      className="num"
                      value={r.amountText}
                      onChange={(e) => update([r.key], () => ({ amountText: e.target.value }))}
                    />
                  </td>
                  <td>
                    <input
                      list="dl-import-cats"
                      aria-invalid={missing && !r.category.trim()}
                      value={r.category}
                      onChange={(e) => update([r.key], () => ({ category: e.target.value, sub: "" }))}
                    />
                  </td>
                  <td>
                    <input
                      list={subList(r.category)}
                      aria-invalid={missing && !r.sub.trim()}
                      value={r.sub}
                      onChange={(e) => update([r.key], () => ({ sub: e.target.value }))}
                    />
                  </td>
                  <td>
                    <input value={r.note} onChange={(e) => update([r.key], () => ({ note: e.target.value }))} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </Modal>
  );
}
