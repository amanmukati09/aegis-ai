"use client";

import { useCallback, useEffect, useState } from "react";
import { motion } from "framer-motion";
import { Brain, RefreshCw, GraduationCap } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { triageApi, type TriageItem } from "@/lib/platform-api";
import { Card, SeverityBadge, StatusBadge } from "@/components/ui";

/**
 * RL triage: incidents ranked by a Q-learning agent. Priority is learned from resolution
 * outcomes (train button feeds resolved incidents back into the policy). Cold-start uses a
 * severity rule until the agent has seen a state.
 */
export default function TriagePage() {
  const { token } = useAuth();
  const [items, setItems] = useState<TriageItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [training, setTraining] = useState(false);
  const [trainMsg, setTrainMsg] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      setItems(await triageApi.queue(token));
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    load();
  }, [load]);

  async function onTrain() {
    if (!token) return;
    setTraining(true);
    setTrainMsg(null);
    try {
      const r = await triageApi.train(token);
      setTrainMsg(`Trained on ${r.trained_on} resolved incidents · ${r.q_states} learned states`);
      await load();
    } catch {
      setTrainMsg("Training failed");
    } finally {
      setTraining(false);
    }
  }

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight">
            <Brain className="h-6 w-6 text-accent" /> RL Triage
          </h1>
          <p className="mt-1 text-sm text-ink-soft">
            Incidents ranked by a reinforcement-learning agent that learns from resolution speed.
          </p>
        </div>
        <div className="flex gap-2">
          <button onClick={load} className="btn-ghost flex h-9 items-center gap-2 px-3 text-sm">
            <RefreshCw className={"h-4 w-4 " + (loading ? "animate-spin" : "")} /> Refresh
          </button>
          <button
            onClick={onTrain}
            disabled={training}
            className="btn-accent flex h-9 items-center gap-2 px-3 text-sm disabled:opacity-50"
          >
            <GraduationCap className="h-4 w-4" /> {training ? "Training…" : "Train"}
          </button>
        </div>
      </div>

      {trainMsg && <p className="mb-4 text-sm text-ink-soft">{trainMsg}</p>}

      {items.length === 0 && !loading ? (
        <Card>
          <p className="text-sm text-ink-soft">No incidents to triage yet.</p>
        </Card>
      ) : (
        <div className="space-y-3">
          {items.map((it, i) => (
            <motion.div
              key={it.incident_id}
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: i * 0.03 }}
            >
              <Card className="flex items-center gap-4 p-4">
                <PriorityDial priority={it.priority} />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{it.title || it.incident_id}</p>
                  <div className="mt-1 flex flex-wrap items-center gap-2">
                    <SeverityBadge severity={it.severity} />
                    <StatusBadge status={it.status} />
                    {it.component && (
                      <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[10px] uppercase text-ink-soft">
                        {it.component}
                      </span>
                    )}
                    <span
                      className="rounded bg-surface-2 px-1.5 py-0.5 text-[10px] uppercase text-ink-soft"
                      title="How the priority was chosen"
                    >
                      {it.policy}
                    </span>
                  </div>
                </div>
                <div className="shrink-0 text-right">
                  <p className="text-xs text-ink-soft">confidence</p>
                  <p className="text-lg font-semibold">{it.confidence.toFixed(0)}%</p>
                </div>
              </Card>
            </motion.div>
          ))}
        </div>
      )}
    </div>
  );
}

function PriorityDial({ priority }: { priority: number }) {
  const color =
    priority >= 5 ? "bg-red-500" : priority === 4 ? "bg-orange-500" : priority === 3 ? "bg-amber-500" : priority === 2 ? "bg-sky-500" : "bg-emerald-500";
  return (
    <div className="flex shrink-0 flex-col items-center">
      <div className={"flex h-11 w-11 items-center justify-center rounded-xl text-lg font-bold text-white " + color}>
        {priority}
      </div>
      <span className="mt-1 text-[10px] uppercase text-ink-soft">P{priority}</span>
    </div>
  );
}
