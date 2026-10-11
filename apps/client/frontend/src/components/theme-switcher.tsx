import { Monitor, Moon, Sun } from "lucide-react";

import { cn } from "@/lib/utils";
import { useClientTheme, type ThemeMode } from "@/lib/client-theme";

interface ThemeSwitcherProps {
  compact?: boolean;
  className?: string;
}

const themeOptions: Array<{
  value: ThemeMode;
  label: string;
  Icon: typeof Sun;
}> = [
  { value: "light", label: "浅色", Icon: Sun },
  { value: "dark", label: "深色", Icon: Moon },
  { value: "system", label: "跟随系统", Icon: Monitor },
];

export function ThemeSwitcher({
  compact = false,
  className,
}: ThemeSwitcherProps) {
  const { theme, setTheme } = useClientTheme();

  return (
    <div
      aria-label="主题模式"
      className={cn(
        "inline-flex items-center gap-0.5 rounded-md border bg-background p-0.5 text-muted-foreground",
        className,
      )}
      role="group"
    >
      {themeOptions.map(({ value, label, Icon }) => (
        <button
          key={value}
          type="button"
          aria-label={`${label}模式`}
          aria-pressed={theme === value}
          title={`${label}模式`}
          onClick={() => setTheme(value)}
          className={cn(
            "inline-flex h-7 items-center justify-center gap-1.5 rounded px-2 text-xs transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1",
            theme === value
              ? "bg-muted text-foreground shadow-sm"
              : "hover:bg-accent hover:text-accent-foreground",
            compact && "size-7 px-0",
          )}
        >
          <Icon aria-hidden="true" className="size-3.5 shrink-0" />
          {compact ? <span className="sr-only">{label}</span> : label}
        </button>
      ))}
    </div>
  );
}
