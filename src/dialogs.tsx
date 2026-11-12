import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

type ModalProps = {
  title: string;
  size?: "small" | "medium" | "large" | "full";
  onClose: () => void;
  footer?: ReactNode;
  children: ReactNode;
};

/** 模态框：Esc 关闭；打开时若内部没有自动聚焦的控件，则聚焦对话框本身以接收 Esc。 */
export function Modal({ title, size = "medium", onClose, footer, children }: ModalProps) {
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (box.current && !box.current.contains(document.activeElement)) box.current.focus();
  }, []);
  return (
    <div className="overlay">
      <div
        ref={box}
        className={`modal modal-${size}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        onKeyDown={(e) => {
          if (e.key === "Escape") {
            e.stopPropagation();
            onClose();
          }
        }}
      >
        <header className="modal-head">{title}</header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-foot">{footer}</footer>}
      </div>
    </div>
  );
}

type Request =
  | { id: number; kind: "alert"; title: string; message: string; resolve: (v: void) => void }
  | { id: number; kind: "prompt"; title: string; message: string; initial: string; resolve: (v: string | null) => void }
  | { id: number; kind: "choose"; title: string; message: string; buttons: string[]; resolve: (v: number) => void };

type Dialogs = {
  alert(message: string, title?: string): Promise<void>;
  /** 取消返回 null，确认返回去掉首尾空白的文本 */
  prompt(title: string, message: string, initial?: string): Promise<string | null>;
  /** 返回按钮序号，Esc 返回 -1 */
  choose(title: string, message: string, buttons: string[]): Promise<number>;
};

const DialogContext = createContext<Dialogs | null>(null);

export function useDialogs(): Dialogs {
  const d = useContext(DialogContext);
  if (!d) throw new Error("useDialogs 必须在 DialogProvider 内使用");
  return d;
}

let seq = 0;

export function DialogProvider({ children }: { children: ReactNode }) {
  const [stack, setStack] = useState<Request[]>([]);
  const push = useCallback(<T,>(make: (id: number, resolve: (v: T) => void) => Request) => {
    return new Promise<T>((resolve) => {
      const id = ++seq;
      const done = (v: T) => {
        setStack((s) => s.filter((r) => r.id !== id));
        resolve(v);
      };
      setStack((s) => [...s, make(id, done)]);
    });
  }, []);
  const api = useMemo<Dialogs>(
    () => ({
      alert: (message, title = "提示") => push<void>((id, resolve) => ({ id, kind: "alert", title, message, resolve })),
      prompt: (title, message, initial = "") =>
        push<string | null>((id, resolve) => ({ id, kind: "prompt", title, message, initial, resolve })),
      choose: (title, message, buttons) =>
        push<number>((id, resolve) => ({ id, kind: "choose", title, message, buttons, resolve })),
    }),
    [push],
  );
  return (
    <DialogContext.Provider value={api}>
      {children}
      {stack.map((r) => (
        <DialogView key={r.id} req={r} />
      ))}
    </DialogContext.Provider>
  );
}

function DialogView({ req }: { req: Request }) {
  const [text, setText] = useState(req.kind === "prompt" ? req.initial : "");
  if (req.kind === "alert") {
    return (
      <Modal
        title={req.title}
        size="small"
        onClose={() => req.resolve()}
        footer={
          <button className="primary" autoFocus onClick={() => req.resolve()}>
            确定
          </button>
        }
      >
        <p className="message">{req.message}</p>
      </Modal>
    );
  }
  if (req.kind === "choose") {
    return (
      <Modal
        title={req.title}
        size="small"
        onClose={() => req.resolve(-1)}
        footer={req.buttons.map((b, i) => (
          <button key={b} className={i === 0 ? "primary" : undefined} autoFocus={i === 0} onClick={() => req.resolve(i)}>
            {b}
          </button>
        ))}
      >
        <p className="message">{req.message}</p>
      </Modal>
    );
  }
  const ok = () => req.resolve(text.trim());
  return (
    <Modal
      title={req.title}
      size="small"
      onClose={() => req.resolve(null)}
      footer={
        <>
          <button onClick={() => req.resolve(null)}>取消</button>
          <button className="primary" onClick={ok}>
            确定
          </button>
        </>
      }
    >
      <label className="field">
        <span>{req.message}</span>
        <input autoFocus value={text} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => e.key === "Enter" && ok()} />
      </label>
    </Modal>
  );
}
