import { open as openDialog, save as saveDialog } from "@tauri-apps/plugin-dialog";
import { useEffect, useState } from "react";
import { api, errorText, type RuleConfig, type RuleDef, type TextMatch } from "./api";
import { Modal, useDialogs } from "./dialogs";

const RULES_KEY = "cashflow.rulesPath";
const YAML_FILTER = [{ name: "规则配置 (YAML)", extensions: ["yaml", "yml"] }];

export function lastRulesPath(): string | undefined {
  try {
    return localStorage.getItem(RULES_KEY) ?? undefined;
  } catch {
    return undefined;
  }
}

export function rememberRulesPath(path: string) {
  try {
    localStorage.setItem(RULES_KEY, path);
  } catch {
    // 只是记住上次的位置，失败无妨
  }
}

export { YAML_FILTER };

const icFlag = (t: TextMatch) => (t.ignoreCase ?? true ? "i" : "");
function tmSummary(t: TextMatch): string {
  if (t.regex != null) return `regex:/${t.regex}/${icFlag(t)}`;
  if (t.equals != null) return `equals:"${t.equals}"${icFlag(t)}`;
  if (t.contains != null) return `contains:"${t.contains}"${icFlag(t)}`;
  return "";
}

export function whenSummary(r: RuleDef): string {
  const w = r.when;
  const parts: string[] = [];
  if (w.payee) parts.push(`payee=${tmSummary(w.payee)}`);
  if (w.item) parts.push(`item=${tmSummary(w.item)}`);
  if (w.note) parts.push(`note=${tmSummary(w.note)}`);
  if (w.amount) parts.push(`amount=${w.amount}`);
  return parts.join("; ");
}

export function thenSummary(r: RuleDef): string {
  const t = r.then;
  const parts: string[] = [];
  if (t.drop) parts.push("drop");
  if (t.category != null) parts.push(`cat=${t.category}`);
  if (t.sub != null) parts.push(`sub=${t.sub}`);
  if (t.noteReplace?.regex != null) parts.push(`noteReplace(/${t.noteReplace.regex}/→${t.noteReplace.with ?? ""})`);
  return parts.join(" ");
}

export function RulesEditor({ onClose }: { onClose: () => void }) {
  const dialogs = useDialogs();
  const [path, setPath] = useState<string | null>(null);
  const [version, setVersion] = useState(1);
  const [defCategory, setDefCategory] = useState("");
  const [defSub, setDefSub] = useState("");
  const [noteOrder, setNoteOrder] = useState("note,item,payee");
  const [onlyAppend, setOnlyAppend] = useState(false);
  const [rules, setRules] = useState<RuleDef[]>([]);
  const [sel, setSel] = useState(-1);
  const [editing, setEditing] = useState<{ index: number | null; rule: RuleDef } | null>(null);
  const [status, setStatus] = useState("");

  const load = async (p: string, quiet = false) => {
    try {
      const cfg = await api.loadRules(p);
      setPath(p);
      setVersion(cfg.version);
      const d = cfg.defaults ?? {};
      setDefCategory(d.category ?? "");
      setDefSub(d.sub ?? "");
      setNoteOrder((d.noteFallbackOrder ?? []).join(","));
      setOnlyAppend(d.onlyAppendFromLastDate ?? false);
      setRules(cfg.rules.map((r) => ({ ...r, when: r.when ?? {}, then: r.then ?? {} })));
      setSel(-1);
      setStatus("");
      rememberRulesPath(p);
    } catch (e) {
      if (!quiet) dialogs.alert(`读取失败：${errorText(e)}`);
    }
  };

  useEffect(() => {
    const p = lastRulesPath();
    if (p) load(p, true);
    // 只在打开编辑器时自动载入上次用过的规则文件
  }, []);

  const collect = (): RuleConfig => ({
    version,
    defaults: {
      category: defCategory,
      sub: defSub,
      noteFallbackOrder: noteOrder
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean),
      onlyAppendFromLastDate: onlyAppend,
    },
    rules,
  });

  const writeTo = async (p: string) => {
    try {
      await api.saveRules(p, collect());
      setPath(p);
      rememberRulesPath(p);
      setStatus(`已保存到 ${p}`);
    } catch (e) {
      dialogs.alert(`保存失败：${errorText(e)}`);
    }
  };
  const saveAs = async () => {
    const p = await saveDialog({ defaultPath: path ?? "rules.yaml", filters: YAML_FILTER });
    if (p) writeTo(/\.ya?ml$/i.test(p) ? p : `${p}.yaml`);
  };
  const openFile = async () => {
    const p = await openDialog({ filters: YAML_FILTER, defaultPath: path ?? undefined });
    if (typeof p === "string") load(p);
  };
  const move = (d: -1 | 1) => {
    const j = sel + d;
    if (sel < 0 || j < 0 || j >= rules.length) return;
    const next = [...rules];
    [next[sel], next[j]] = [next[j], next[sel]];
    setRules(next);
    setSel(j);
  };

  return (
    <Modal
      title="规则编辑器"
      size="large"
      onClose={onClose}
      footer={
        <>
          <span className="muted">{status || (path ? `当前文件：${path}` : "未保存的新规则")}</span>
          <button onClick={onClose}>关闭</button>
        </>
      }
    >
      <div className="toolbar">
        <button onClick={openFile}>打开…</button>
        <button onClick={() => (path ? writeTo(path) : saveAs())}>保存</button>
        <button onClick={saveAs}>另存为…</button>
      </div>
      <fieldset className="defaults">
        <legend>默认值（规则未指定时使用）</legend>
        <label className="field">
          <span>默认分类</span>
          <input value={defCategory} onChange={(e) => setDefCategory(e.target.value)} />
        </label>
        <label className="field">
          <span>默认子类</span>
          <input value={defSub} onChange={(e) => setDefSub(e.target.value)} />
        </label>
        <label className="field">
          <span>备注兜底顺序</span>
          <input value={noteOrder} onChange={(e) => setNoteOrder(e.target.value)} placeholder="note,item,payee" />
        </label>
        <label className="check">
          <input type="checkbox" checked={onlyAppend} onChange={(e) => setOnlyAppend(e.target.checked)} />
          只导入账本最后一天及之后的记录
        </label>
      </fieldset>
      <div className="toolbar">
        <button onClick={() => setEditing({ index: null, rule: { name: "未命名规则", when: {}, then: {} } })}>新增</button>
        <button disabled={sel < 0} onClick={() => setEditing({ index: sel, rule: structuredClone(rules[sel]) })}>
          编辑
        </button>
        <button
          disabled={sel < 0}
          onClick={() => {
            setRules(rules.filter((_, i) => i !== sel));
            setSel(-1);
          }}
        >
          删除
        </button>
        <button disabled={sel <= 0} onClick={() => move(-1)}>
          上移
        </button>
        <button disabled={sel < 0 || sel >= rules.length - 1} onClick={() => move(1)}>
          下移
        </button>
        <span className="muted">按顺序匹配，命中第一条即停止</span>
      </div>
      <div className="table-scroll">
        <table className="grid">
          <thead>
            <tr>
              <th style={{ width: 160 }}>名称</th>
              <th>条件</th>
              <th>动作</th>
            </tr>
          </thead>
          <tbody>
            {rules.map((r, i) => (
              <tr
                key={i}
                className={i === sel ? "selected" : undefined}
                onClick={() => setSel(i)}
                onDoubleClick={() => setEditing({ index: i, rule: structuredClone(r) })}
              >
                <td>{r.name ?? ""}</td>
                <td>{whenSummary(r)}</td>
                <td>{thenSummary(r)}</td>
              </tr>
            ))}
            {rules.length === 0 && (
              <tr className="empty">
                <td colSpan={3}>还没有规则</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {editing && (
        <RuleEditDialog
          rule={editing.rule}
          onCancel={() => setEditing(null)}
          onOk={(r) => {
            if (editing.index === null) {
              setRules([...rules, r]);
              setSel(rules.length);
            } else {
              setRules(rules.map((x, i) => (i === editing.index ? r : x)));
            }
            setEditing(null);
          }}
        />
      )}
    </Modal>
  );
}

type MatchKind = "" | "contains" | "equals" | "regex";
type MatchForm = { kind: MatchKind; value: string; ignoreCase: boolean };

function toForm(t?: TextMatch | null): MatchForm {
  const ignoreCase = t?.ignoreCase ?? true;
  if (t?.regex != null) return { kind: "regex", value: t.regex, ignoreCase };
  if (t?.equals != null) return { kind: "equals", value: t.equals, ignoreCase };
  if (t?.contains != null) return { kind: "contains", value: t.contains, ignoreCase };
  return { kind: "", value: "", ignoreCase: true };
}

function fromForm(f: MatchForm): TextMatch | null {
  const v = f.value.trim();
  if (!f.kind || !v) return null;
  return { [f.kind]: v, ignoreCase: f.ignoreCase } as TextMatch;
}

const blankToNull = (s: string) => s.trim() || null;

function MatchFields({ label, form, onChange }: { label: string; form: MatchForm; onChange: (f: MatchForm) => void }) {
  return (
    <div className="match-row">
      <span>{label}</span>
      <select aria-label={`${label}匹配方式`} value={form.kind} onChange={(e) => onChange({ ...form, kind: e.target.value as MatchKind })}>
        <option value="">（不限）</option>
        <option value="contains">包含</option>
        <option value="equals">等于</option>
        <option value="regex">正则</option>
      </select>
      <input aria-label={`${label}匹配内容`} value={form.value} onChange={(e) => onChange({ ...form, value: e.target.value })} />
      <label className="check" title="正则始终区分大小写">
        <input type="checkbox" checked={form.ignoreCase} onChange={(e) => onChange({ ...form, ignoreCase: e.target.checked })} />
        忽略大小写
      </label>
    </div>
  );
}

function RuleEditDialog({ rule, onOk, onCancel }: { rule: RuleDef; onOk: (r: RuleDef) => void; onCancel: () => void }) {
  const [name, setName] = useState(rule.name ?? "");
  const [payee, setPayee] = useState(toForm(rule.when.payee));
  const [item, setItem] = useState(toForm(rule.when.item));
  const [note, setNote] = useState(toForm(rule.when.note));
  const [amount, setAmount] = useState(rule.when.amount ?? "");
  const [drop, setDrop] = useState(rule.then.drop === true);
  const [category, setCategory] = useState(rule.then.category ?? "");
  const [sub, setSub] = useState(rule.then.sub ?? "");
  const [nrRegex, setNrRegex] = useState(rule.then.noteReplace?.regex ?? "");
  const [nrWith, setNrWith] = useState(rule.then.noteReplace?.with ?? "");
  const [error, setError] = useState("");

  const ok = () => {
    if (!name.trim()) return setError("名称不能为空");
    const nr = nrRegex.trim();
    const nw = nrWith.trim();
    onOk({
      name: name.trim(),
      when: { payee: fromForm(payee), item: fromForm(item), note: fromForm(note), amount: blankToNull(amount) },
      then: {
        drop: drop || null,
        category: blankToNull(category),
        sub: blankToNull(sub),
        noteReplace: nr || nw ? { regex: nr || null, with: nw || null } : null,
      },
    });
  };

  return (
    <Modal
      title="编辑规则"
      onClose={onCancel}
      footer={
        <>
          <span className="error">{error}</span>
          <button onClick={onCancel}>取消</button>
          <button className="primary" onClick={ok}>
            确定
          </button>
        </>
      }
    >
      <div className="form">
        <label className="field">
          <span>名称</span>
          <input autoFocus value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <h4>条件（全部满足才算命中）</h4>
        <MatchFields label="交易对方" form={payee} onChange={setPayee} />
        <MatchFields label="商品" form={item} onChange={setItem} />
        <MatchFields label="备注" form={note} onChange={setNote} />
        <label className="field">
          <span>金额条件</span>
          <input value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="如 <0、>=100、==9.9" />
        </label>
        <h4>动作</h4>
        <label className="check">
          <input type="checkbox" checked={drop} onChange={(e) => setDrop(e.target.checked)} />
          丢弃（不导入）
        </label>
        <label className="field">
          <span>分类</span>
          <input value={category} onChange={(e) => setCategory(e.target.value)} />
        </label>
        <label className="field">
          <span>子类</span>
          <input value={sub} onChange={(e) => setSub(e.target.value)} />
        </label>
        <label className="field">
          <span>备注替换·正则</span>
          <input value={nrRegex} onChange={(e) => setNrRegex(e.target.value)} />
        </label>
        <label className="field">
          <span>替换为</span>
          <input value={nrWith} onChange={(e) => setNrWith(e.target.value)} placeholder="可用 $1 引用分组" />
        </label>
      </div>
    </Modal>
  );
}
