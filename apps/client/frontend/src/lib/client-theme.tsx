import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

export type ThemeMode = "light" | "dark" | "system";
export type ResolvedTheme = Exclude<ThemeMode, "system">;

const THEME_STORAGE_KEY = "cqg-client-theme";
const ThemeContext = createContext<ThemeContextValue | undefined>(undefined);

interface ThemeContextValue {
  theme: ThemeMode;
  resolvedTheme: ResolvedTheme;
  setTheme: (theme: ThemeMode) => void;
}

interface ClientThemeProviderProps {
  children: ReactNode;
}

function isThemeMode(value: string | null): value is ThemeMode {
  return value === "light" || value === "dark" || value === "system";
}

function readStoredTheme(): ThemeMode {
  if (typeof window === "undefined") return "system";

  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    return isThemeMode(stored) ? stored : "system";
  } catch {
    return "system";
  }
}

function getSystemTheme(): ResolvedTheme {
  if (
    typeof window === "undefined" ||
    typeof window.matchMedia !== "function"
  ) {
    return "light";
  }
  return window.matchMedia("(prefers-color-scheme: dark)").matches
    ? "dark"
    : "light";
}

function persistTheme(theme: ThemeMode) {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // Keep theme switching functional when browser storage is unavailable.
  }
}

function applyTheme(theme: ResolvedTheme) {
  if (typeof document === "undefined") return;

  const root = document.documentElement;
  root.classList.toggle("dark", theme === "dark");
  root.dataset.theme = theme;
  root.style.colorScheme = theme;
}

export function ClientThemeProvider({ children }: ClientThemeProviderProps) {
  const [theme, setThemeState] = useState<ThemeMode>(readStoredTheme);
  const [systemTheme, setSystemTheme] = useState<ResolvedTheme>(getSystemTheme);
  const resolvedTheme = theme === "system" ? systemTheme : theme;

  useEffect(() => {
    // The main window and tray WebView share storage, including before login.
    const syncStoredTheme = (event: StorageEvent) => {
      if (event.key === THEME_STORAGE_KEY || event.key === null) {
        setThemeState(readStoredTheme());
      }
    };
    window.addEventListener("storage", syncStoredTheme);
    return () => window.removeEventListener("storage", syncStoredTheme);
  }, []);

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;

    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
    const updateSystemTheme = (event: MediaQueryListEvent) => {
      setSystemTheme(event.matches ? "dark" : "light");
    };

    setSystemTheme(mediaQuery.matches ? "dark" : "light");
    mediaQuery.addEventListener("change", updateSystemTheme);
    return () => mediaQuery.removeEventListener("change", updateSystemTheme);
  }, []);

  useEffect(() => applyTheme(resolvedTheme), [resolvedTheme]);

  const setTheme = useCallback((nextTheme: ThemeMode) => {
    persistTheme(nextTheme);
    setThemeState(nextTheme);
  }, []);

  const value = useMemo(
    () => ({ theme, resolvedTheme, setTheme }),
    [theme, resolvedTheme, setTheme],
  );

  return (
    <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
  );
}

export function useClientTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  const [fallbackTheme, setFallbackTheme] =
    useState<ThemeMode>(readStoredTheme);
  const [fallbackSystemTheme, setFallbackSystemTheme] =
    useState<ResolvedTheme>(getSystemTheme);
  const fallbackResolvedTheme =
    fallbackTheme === "system" ? fallbackSystemTheme : fallbackTheme;

  useEffect(() => {
    if (context || typeof window.matchMedia !== "function") return;

    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
    const updateSystemTheme = (event: MediaQueryListEvent) => {
      setFallbackSystemTheme(event.matches ? "dark" : "light");
    };

    mediaQuery.addEventListener("change", updateSystemTheme);
    return () => mediaQuery.removeEventListener("change", updateSystemTheme);
  }, [context]);

  useEffect(() => {
    if (!context) applyTheme(fallbackResolvedTheme);
  }, [context, fallbackResolvedTheme]);

  const setFallbackThemeMode = useCallback((nextTheme: ThemeMode) => {
    persistTheme(nextTheme);
    setFallbackTheme(nextTheme);
    applyTheme(nextTheme === "system" ? getSystemTheme() : nextTheme);
  }, []);

  return (
    context ?? {
      theme: fallbackTheme,
      resolvedTheme: fallbackResolvedTheme,
      setTheme: setFallbackThemeMode,
    }
  );
}
