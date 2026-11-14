"use client";

import { useRef, useState } from "react";
import { getChannelConfig, replaceChannelConfig, type ChannelConfig } from "@/lib/api";

type Props = { onClose: () => void; onApplied: () => Promise<void> };

export default function JsonConfigModal({ onClose, onApplied }: Props) {
  const [jsonText, setJsonText] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  async function exportJson() {
    setBusy(true);
    setMessage(null);
    try {
      const config = await getChannelConfig();
      const blob = new Blob([`${JSON.stringify(config, null, 2)}\n`], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "channels.json";
      link.click();
      URL.revokeObjectURL(url);
      setMessage("Current configuration downloaded.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }

  async function loadCurrentJson() {
    setBusy(true);
    setMessage(null);
    try {
      const config = await getChannelConfig();
      setJsonText(`${JSON.stringify(config, null, 2)}\n`);
      setMessage("Current configuration loaded into the editor. Edit it, then choose Apply JSON.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }

  async function readFile(file?: File) {
    if (!file) return;
    setMessage(null);
    try {
      const text = await file.text();
      JSON.parse(text);
      setJsonText(text);
      setMessage(`${file.name} loaded. Review it, then choose Apply JSON.`);
    } catch (error) {
      setMessage(error instanceof Error ? `Could not read valid JSON: ${error.message}` : "Could not read valid JSON.");
    }
  }

  async function applyJson() {
    setBusy(true);
    setMessage(null);
    try {
      const config = JSON.parse(jsonText) as ChannelConfig;
      if (!config || config.version !== 1 || !Array.isArray(config.genres)) {
        throw new Error('Expected an object with "version": 1 and a "genres" array.');
      }
      if (!window.confirm("Replace the current genres and channels with this JSON? This does not affect remote pairing.")) return;
      await replaceChannelConfig(config);
      await onApplied();
      setMessage("Configuration applied successfully.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop json-config-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section className="modal-card json-config-card" role="dialog" aria-modal="true" aria-labelledby="json-config-title">
        <header className="modal-header">
          <h2 id="json-config-title">Channel JSON</h2>
          <button type="button" className="modal-close-btn" onClick={onClose} aria-label="Close">✕</button>
        </header>
        <div className="json-config-body">
          <p>Import, export, or edit the complete channel configuration. This is separate from Edit Channels.</p>
          <div className="json-config-actions">
            <button type="button" className="edit-btn" onClick={loadCurrentJson} disabled={busy}>Load current JSON</button>
            <button type="button" className="edit-btn" onClick={exportJson} disabled={busy}>Export current JSON</button>
            <button type="button" className="edit-btn" onClick={() => fileInput.current?.click()} disabled={busy}>Choose JSON file</button>
            <input ref={fileInput} type="file" accept=".json,application/json" hidden onChange={(event) => { void readFile(event.target.files?.[0]); event.currentTarget.value = ""; }} />
          </div>
          <textarea className="json-config-editor" value={jsonText} onChange={(event) => setJsonText(event.target.value)} spellCheck={false} aria-label="Channel configuration JSON" placeholder={'Load a JSON file or paste/edit JSON here.\n\nExpected format: { "version": 1, "genres": [...] }'} />
          {message && <div className="json-config-message" role="status">{message}</div>}
          <div className="json-config-footer">
            <button type="button" className="edit-btn" onClick={onClose}>Cancel</button>
            <button type="button" className="json-apply-btn" onClick={() => void applyJson()} disabled={busy || !jsonText.trim()}>Apply JSON</button>
          </div>
        </div>
      </section>
    </div>
  );
}
