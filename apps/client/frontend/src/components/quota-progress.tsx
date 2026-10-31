import { Progress } from "./ui/progress";

export function QuotaProgress({
  value,
  label,
}: {
  value: number;
  label: string;
}) {
  const percentage = `${value.toFixed(1)}%`;
  return (
    <div className="flex min-w-0 items-center gap-2">
      <Progress
        className="h-1.5 min-w-0 flex-1"
        value={value}
        aria-label={label}
        aria-valuetext={percentage}
      />
      <span
        aria-hidden="true"
        className="shrink-0 text-[11px] tabular-nums text-muted-foreground"
        title={percentage}
      >
        {percentage}
      </span>
    </div>
  );
}
