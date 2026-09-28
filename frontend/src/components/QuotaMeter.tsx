import { formatTokens, isUnlimitedQuota, quotaPercent } from "../utils/chat";
import type { ChatQuotaDto } from "../types/api";

/** "used / limit tokens this month" with a bar that turns amber at 80% and red when exhausted. */
export function QuotaMeter({ quota, label = "Chat tokens this month" }: { quota: ChatQuotaDto; label?: string }) {
  if (isUnlimitedQuota(quota)) {
    return (
      <div className="quota-meter">
        <div className="quota-meter-row">
          <span>{label}</span>
          <span className="quota-meter-value">{formatTokens(quota.used)} used · unlimited</span>
        </div>
      </div>
    );
  }

  const percent = quotaPercent(quota);
  const level = percent >= 100 ? " quota-meter-full" : percent >= 80 ? " quota-meter-high" : "";
  const valueText = `${formatTokens(quota.used)} of ${formatTokens(quota.limit)} tokens used`;

  return (
    <div className={`quota-meter${level}`}>
      <div className="quota-meter-row">
        <span>{label}</span>
        <span className="quota-meter-value">
          {formatTokens(quota.used)} / {formatTokens(quota.limit)}
        </span>
      </div>
      <div
        className="progress-bar"
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={quota.limit}
        aria-valuenow={Math.min(quota.used, quota.limit)}
        aria-valuetext={valueText}
      >
        <div className="progress-bar-fill" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}
