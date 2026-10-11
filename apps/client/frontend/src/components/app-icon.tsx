import { cn } from "@/lib/utils";

// Share the source artwork used to generate the native application icons.
const appIconUrl = new URL("../../assets/app-icon.svg", import.meta.url).href;

export function AppIcon({ className }: { className?: string }) {
  return (
    <img
      src={appIconUrl}
      alt="Codex Quota Guard 图标"
      width={36}
      height={36}
      draggable={false}
      className={cn("object-contain", className)}
    />
  );
}
