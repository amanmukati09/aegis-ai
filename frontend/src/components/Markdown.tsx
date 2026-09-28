"use client";

import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

/**
 * Renders assistant replies as formatted Markdown (headings, lists, code, tables).
 * Styling is inline via component overrides so we don't depend on the Tailwind
 * typography plugin.
 */
export function Markdown({ content }: { content: string }) {
  return (
    <div className="text-sm leading-relaxed text-ink">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: (p) => <h1 className="mb-2 mt-3 text-base font-semibold" {...p} />,
          h2: (p) => <h2 className="mb-2 mt-3 text-sm font-semibold" {...p} />,
          h3: (p) => <h3 className="mb-1 mt-2 text-sm font-semibold" {...p} />,
          p: (p) => <p className="mb-2 last:mb-0" {...p} />,
          ul: (p) => <ul className="mb-2 ml-5 list-disc space-y-1" {...p} />,
          ol: (p) => <ol className="mb-2 ml-5 list-decimal space-y-1" {...p} />,
          li: (p) => <li className="pl-1" {...p} />,
          a: (p) => <a className="text-accent underline" target="_blank" rel="noreferrer" {...p} />,
          strong: (p) => <strong className="font-semibold" {...p} />,
          blockquote: (p) => (
            <blockquote className="my-2 border-l-2 border-black/10 pl-3 text-ink-soft" {...p} />
          ),
          code: ({ className, children, ...props }) => {
            const isBlock = className?.includes("language-");
            if (isBlock) {
              return (
                <code
                  className="block overflow-x-auto rounded-lg bg-ink/90 p-3 font-mono text-xs text-white"
                  {...props}
                >
                  {children}
                </code>
              );
            }
            return (
              <code className="rounded bg-black/5 px-1.5 py-0.5 font-mono text-[0.8em]" {...props}>
                {children}
              </code>
            );
          },
          pre: (p) => <pre className="mb-2" {...p} />,
          table: (p) => (
            <div className="mb-2 overflow-x-auto">
              <table className="w-full border-collapse text-xs" {...p} />
            </div>
          ),
          th: (p) => <th className="border border-black/10 bg-surface-muted px-2 py-1 text-left font-medium" {...p} />,
          td: (p) => <td className="border border-black/10 px-2 py-1" {...p} />,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}
