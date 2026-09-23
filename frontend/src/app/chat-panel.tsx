"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import styles from "./chat-panel.module.css";

type Message = { role: "user" | "assistant"; content: string };

export default function ChatPanel({ context }: { context: string }) {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<Message[]>([
    { role: "assistant", content: "Привет! Я помогу разобраться с районами, мероприятиями и вашим сценарием. Что хотите узнать?" },
  ]);
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" }); }, [messages, pending, error, open]);

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = draft.trim();
    if (!content || pending) return;
    const next: Message[] = [...messages, { role: "user", content }];
    setMessages(next);
    setDraft("");
    setError("");
    setPending(true);
    try {
      const response = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages: next.slice(-12), context }),
      });
      const data = await response.json();
      if (!response.ok || typeof data.answer !== "string") throw new Error(data.error || "Не удалось получить ответ.");
      setMessages((current) => [...current, { role: "assistant", content: data.answer }]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось получить ответ.");
    } finally {
      setPending(false);
    }
  }

  return <>
    {!open && <button className={styles.launcher} type="button" onClick={() => setOpen(true)} aria-label="Открыть чат">✦ <span>Спросить ИИ</span></button>}
    {open && <aside className={styles.panel} data-chat-panel aria-label="Чат с помощником">
      <header className={styles.header}>
        <div className={styles.avatar} aria-hidden="true">✦</div>
        <div><span className={styles.kicker}>ПОМОЩНИК</span><h2>Спросите про город</h2><small>Районы · мероприятия · ваш сценарий</small></div>
        <button className={styles.close} type="button" onClick={() => setOpen(false)} aria-label="Закрыть чат">×</button>
      </header>
      <div className={styles.messages} role="log" aria-live="polite" aria-relevant="additions text">
        {messages.map((message, index) => <div key={index} className={`${styles.message} ${message.role === "user" ? styles.user : styles.assistant}`}><span>{message.content}</span></div>)}
        {pending && <div className={`${styles.message} ${styles.assistant}`}><span>Думаю…</span></div>}
        {error && <p className={styles.error} role="alert">{error}</p>}
        <div ref={bottomRef} />
      </div>
      <form className={styles.composer} onSubmit={send}>
        <label className={styles.srOnly} htmlFor="chat-message">Сообщение помощнику</label>
        <textarea id="chat-message" value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => {
          if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); }
        }} placeholder="Спросите о районе или сценарии…" maxLength={1500} rows={2} />
        <button type="submit" disabled={!draft.trim() || pending} aria-label="Отправить сообщение">↑</button>
      </form>
      <p className={styles.note}>Ответы ИИ могут быть неточными. Проверяйте цифры в расчёте.</p>
    </aside>}
  </>;
}
