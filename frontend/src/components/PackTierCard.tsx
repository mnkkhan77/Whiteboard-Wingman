import { TierBadge } from "./TierBadge";
import { formatBytes, formatExtensions, formatPackQuota } from "../utils/packs";
import type { PackLimitsDto } from "../types/api";

export function PackTierCard({ limits }: { limits: PackLimitsDto }) {
  const stats = [
    { label: "Packs used", value: formatPackQuota(limits.packsUsed, limits.maxPacks) },
    { label: "Max file size", value: formatBytes(limits.maxFileBytes) },
    { label: "Max pages", value: limits.maxPages },
    { label: "Scanned docs (OCR)", value: limits.ocrEnabled ? "Yes" : "No" },
  ];

  return (
    <section className="card pack-card">
      <div className="pack-card-header">
        <h2>Your plan</h2>
        <TierBadge tier={limits.tier} />
      </div>
      <div className="pack-tier-grid">
        {stats.map((s) => (
          <div key={s.label} className="stat-box">
            <span className="stat-value">{s.value}</span>
            <span className="stat-label">{s.label}</span>
          </div>
        ))}
      </div>
      <p className="progress-label">Allowed formats: {formatExtensions(limits.allowedExtensions)}</p>
    </section>
  );
}
