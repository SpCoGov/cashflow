import { useRef, useState, type KeyboardEvent } from "react";
import type { Category, Entry } from "./api";
import { Modal } from "./dialogs";
import { daysInMonth, fmtDateTime, fmtYuan, parseDateTime, parseQuickAmount, yuanToCents, ymText } from "./format";

/** 在候选列表里回车选中时，不冒泡成「提交」。 */
const keepEnter = (e: KeyboardEvent) => e.key === "Enter" && e.stopPropagation();

type CategoryFieldsProps = {
  taxonomy: Category[];
  category: string;
  sub: string;
  onChange: (category: string, sub: string) => void;
};

/** 类别 + 子类：可从已有分类中选，也可直接输入新的。 */
export function CategoryFields({ taxonomy, category, sub, onChange }: CategoryFieldsProps) {
  const subs = taxonomy.find((c) => c.name === category.trim())?.subs ?? [];
  return (
    <>
      <label className="field">
        <span>类别</span>
        <input
          list="dl-categories"
          value={category}
          onKeyDown={keepEnter}
          onChange={(e) => {
            const next = taxonomy.find((c) => c.name === e.target.value.trim());
            onChange(e.target.value, next?.subs.includes(sub.trim()) ? sub : "");
          }}
        />
        <datalist id="dl-categories">
          {taxonomy.map((c) => (
            <option key={c.name} value={c.name} />
          ))}
        </datalist>
      </label>
      <label className="field">
        <span>子类别</span>
        <input list="dl-subs" value={sub} onKeyDown={keepEnter} onChange={(e) => onChange(category, e.target.value)} />
        <datalist id="dl-subs">
          {subs.map((s) => (
            <option key={s} value={s} />
          ))}
        </datalist>
      </label>
    </>
  );
}

type EditProps = {
  initial?: Entry;
  taxonomy: Category[];
  onOk: (e: Entry) => void;
  onCancel: () => void;
};

export function EditRecordDialog({ initial, taxonomy, onOk, onCancel }: EditProps) {
  const [tsText, setTsText] = useState(fmtDateTime(initial?.ts ?? Date.now()));
  const [amountText, setAmountText] = useState(initial ? fmtYuan(initial.amount) : "0.00");
  const [category, setCategory] = useState(initial?.category ?? "");
  const [sub, setSub] = useState(initial?.sub ?? "");
  const [note, setNote] = useState(initial?.note ?? "");
  const [error, setError] = useState("");

  const submit = () => {
    const ts = parseDateTime(tsText);
    const amount = yuanToCents(amountText);
    if (ts === null) return setError("时间格式不正确，示例：2025-01-31 12:34:56");
    if (amount === null) return setError("金额格式不正确");
    if (!category.trim()) return setError("类别不能为空");
    if (!sub.trim()) return setError("子类别不能为空");
    onOk({ ts, amount, category: category.trim(), sub: sub.trim(), note: note.trim() });
  };
  const enter = (e: KeyboardEvent) => e.key === "Enter" && submit();

  return (
    <Modal
      title={initial ? "编辑记录" : "添加记录"}
      onClose={onCancel}
      footer={
        <>
          <span className="error">{error}</span>
          <button onClick={onCancel}>取消</button>
          <button className="primary" onClick={submit}>
            确定
          </button>
        </>
      }
    >
      <div className="form" onKeyDown={enter}>
        <label className="field">
          <span>时间</span>
          <input value={tsText} onChange={(e) => setTsText(e.target.value)} />
        </label>
        <label className="field">
          <span>金额（元）</span>
          <input autoFocus onFocus={(e) => e.target.select()} value={amountText} onChange={(e) => setAmountText(e.target.value)} />
        </label>
        <CategoryFields
          taxonomy={taxonomy}
          category={category}
          sub={sub}
          onChange={(c, s) => {
            setCategory(c);
            setSub(s);
          }}
        />
        <label className="field">
          <span>备注</span>
          <input value={note} onChange={(e) => setNote(e.target.value)} />
        </label>
        <p className="hint">金额正数为收入、负数为支出。</p>
      </div>
    </Modal>
  );
}

type QuickProps = {
  year: number;
  month: number;
  initialDay: number;
  taxonomy: Category[];
  onAdd: (e: Entry) => void;
  onClose: (lastDay: number) => void;
};

/** 连续录入：保留日期与分类，每次添加后清空金额和备注。 */
export function QuickAddDialog({ year, month, initialDay, taxonomy, onAdd, onClose }: QuickProps) {
  const [dayText, setDayText] = useState(String(initialDay));
  const [amountText, setAmountText] = useState("");
  const [category, setCategory] = useState("");
  const [sub, setSub] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState("");
  const [added, setAdded] = useState(0);
  const [lastDay, setLastDay] = useState(initialDay);
  const amountInput = useRef<HTMLInputElement>(null);
  const maxDay = daysInMonth(year, month);

  const add = () => {
    const day = Number(dayText.trim());
    if (!Number.isInteger(day) || day < 1 || day > maxDay) return setError(`非法日：${dayText}。该月应为 1..${maxDay}`);
    const amount = parseQuickAmount(amountText);
    if (!amountText.trim() || amount === null) return setError("金额格式不正确");
    if (!category.trim()) return setError("类别不能为空");
    if (!sub.trim()) return setError("子类别不能为空");
    const now = new Date();
    const ts = new Date(year, month - 1, day, now.getHours(), now.getMinutes(), now.getSeconds(), now.getMilliseconds()).getTime();
    onAdd({ ts, amount, category: category.trim(), sub: sub.trim(), note: note.trim() });
    setLastDay(day);
    setAdded((n) => n + 1);
    setAmountText("");
    setNote("");
    setError("");
    amountInput.current?.focus();
  };

  return (
    <Modal
      title="快速添加"
      onClose={() => onClose(lastDay)}
      footer={
        <>
          <span className={error ? "error" : "muted"}>{error || (added ? `已添加 ${added} 条` : "")}</span>
          <button onClick={() => onClose(lastDay)}>关闭</button>
          <button className="primary" onClick={add}>
            添加下一条
          </button>
        </>
      }
    >
      <div className="form" onKeyDown={(e) => e.key === "Enter" && add()}>
        <label className="field">
          <span>日（{ymText(year, month)}）</span>
          <input value={dayText} onChange={(e) => setDayText(e.target.value)} inputMode="numeric" />
        </label>
        <label className="field">
          <span>金额（元）</span>
          <input ref={amountInput} autoFocus value={amountText} onChange={(e) => setAmountText(e.target.value)} />
        </label>
        <CategoryFields
          taxonomy={taxonomy}
          category={category}
          sub={sub}
          onChange={(c, s) => {
            setCategory(c);
            setSub(s);
          }}
        />
        <label className="field">
          <span>备注</span>
          <input value={note} onChange={(e) => setNote(e.target.value)} />
        </label>
        <p className="hint">金额不带符号记为支出，以 + 开头记为收入；回车添加下一条。</p>
      </div>
    </Modal>
  );
}
