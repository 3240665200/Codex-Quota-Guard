import {
  Alert,
  App,
  Button,
  Modal,
  Progress,
  Select,
  Space,
  Typography,
} from "antd";
import { useEffect, useRef, useState } from "react";
import { getErrorMessage } from "../../components/AdminState";
import type {
  DesktopRelease,
  ReleaseKind,
} from "../../services/desktopReleasesApi";
import {
  discardUpload,
  uploadRelease,
  type UploadProgress,
} from "../../services/resumableUpload";

const size = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
const stages = {
  hashing: "正在准备文件",
  uploading: "正在上传",
  verifying: "正在校验安装包与签名",
};
export function ReleaseUploadModal({
  release,
  kind,
  onClose,
  onComplete,
  onActivity,
}: {
  release: DesktopRelease;
  kind: ReleaseKind;
  onClose: () => void;
  onComplete: () => void;
  onActivity: (active: boolean) => void;
}) {
  const { message } = App.useApp();
  const [target, setTarget] = useState("windows");
  const [arch, setArch] = useState("x86_64");
  const [file, setFile] = useState<File>();
  const [signature, setSignature] = useState<File>();
  const [progress, setProgress] = useState<UploadProgress>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [paused, setPaused] = useState(false);
  const session = useRef<string | undefined>(undefined);
  const controller = useRef<AbortController | undefined>(undefined);
  const running = useRef(false);
  const meter = kind === "meter";
  const maximum = meter ? 64 : 512;
  useEffect(() => () => controller.current?.abort(), []);
  const reset = () => {
    session.current = undefined;
    setProgress(undefined);
    setError("");
    setPaused(false);
  };
  const validation =
    file && (!file.size || file.size > maximum * 1024 * 1024)
      ? `安装包须不超过 ${maximum} MiB。`
      : signature && (!signature.size || signature.size > 32768)
        ? "签名文件不能为空且须小于 32 KiB。"
        : "";
  const pause = () => {
    controller.current?.abort();
    setPaused(true);
  };
  async function start() {
    if (running.current || !file || !signature || validation) return;
    running.current = true;
    setBusy(true);
    onActivity(true);
    setError("");
    setPaused(false);
    const operation = new AbortController();
    controller.current = operation;
    try {
      await uploadRelease({
        release,
        kind,
        target,
        arch,
        file,
        signature,
        signal: operation.signal,
        resumeId: session.current,
        onSession: (value) => {
          session.current = value.id;
        },
        onProgress: setProgress,
      });
      void message.success("安装包上传及签名校验完成");
      onComplete();
    } catch (failure) {
      if (operation.signal.aborted) setPaused(true);
      else {
        setError(getErrorMessage(failure));
        setPaused(true);
      }
    } finally {
      running.current = false;
      setBusy(false);
      onActivity(false);
    }
  }
  async function discard() {
    if (busy) return;
    setBusy(true);
    onActivity(true);
    try {
      if (session.current) await discardUpload(session.current);
      reset();
    } catch (failure) {
      setError(getErrorMessage(failure));
    } finally {
      setBusy(false);
      onActivity(false);
    }
  }
  return (
    <Modal
      open
      centered
      title={`上传${meter ? "统计程序" : "安装包"} · v${release.version}`}
      destroyOnHidden
      styles={{
        body: {
          maxHeight: progress ? "calc(100vh - 350px)" : "calc(100vh - 220px)",
          overflowY: "auto",
        },
      }}
      onCancel={() => {
        pause();
        onClose();
      }}
      footer={
        <div>
          <div style={{ textAlign: "left", marginBottom: 12 }}>
            {progress && (
              <div aria-live="polite">
                <Typography.Text strong>
                  {stages[progress.stage]}
                  {paused ? " · 已暂停" : ""}
                </Typography.Text>
                <Progress
                  percent={Math.round(progress.percent * 10) / 10}
                  status={error ? "exception" : busy ? "active" : "normal"}
                />
                <Typography.Text type="secondary">
                  {progress.stage === "hashing"
                    ? "正在计算文件校验值"
                    : `${size(progress.loaded)} / ${size(progress.total)}`}
                </Typography.Text>
                {!!progress.resumedBytes && (
                  <div>
                    <Typography.Text type="secondary">
                      已恢复 {size(progress.resumedBytes)} 上传进度
                    </Typography.Text>
                  </div>
                )}
              </div>
            )}
            {paused && !error && (
              <Typography.Text type="secondary">
                已暂停，已完成的上传部分已保留。
              </Typography.Text>
            )}
            {(validation || error) && (
              <Alert type="error" title={validation || error} />
            )}
          </div>
          <Space wrap>
            <Button
              disabled={busy}
              onClick={() => {
                onClose();
              }}
            >
              关闭
            </Button>
            {session.current && (
              <Button disabled={busy} onClick={() => void discard()}>
                放弃上传
              </Button>
            )}
            {busy ? (
              <Button
                onClick={pause}
                disabled={progress?.stage === "verifying"}
              >
                暂停
              </Button>
            ) : (
              <Button
                type="primary"
                disabled={!file || !signature || !!validation}
                onClick={() => void start()}
              >
                {paused ? "继续上传" : "开始上传"}
              </Button>
            )}
          </Space>
        </div>
      }
    >
      <Space orientation="vertical" size={16} style={{ width: "100%" }}>
        <Alert
          type="info"
          showIcon
          title="支持断点续传"
          description="上传中断或刷新页面后，重新选择相同安装包和签名文件即可继续。已上传内容保留 24 小时。"
        />
        <label>
          系统
          <Select
            aria-label="安装包系统"
            value={target}
            style={{ width: "100%", marginTop: 6 }}
            disabled={busy}
            onChange={(value) => {
              reset();
              setTarget(value);
              setFile(undefined);
            }}
            options={
              meter
                ? [{ value: "windows", label: "Windows · 统计服务 (.exe)" }]
                : [
                    { value: "windows", label: "Windows · NSIS (.exe)" },
                    { value: "darwin", label: "macOS (.app.tar.gz)" },
                    { value: "linux", label: "Linux (.AppImage)" },
                  ]
            }
          />
        </label>
        <label>
          架构
          <Select
            aria-label="安装包架构"
            value={arch}
            disabled={busy}
            style={{ width: "100%", marginTop: 6 }}
            onChange={(value) => {
              reset();
              setArch(value);
            }}
            options={[
              { value: "x86_64", label: "x64 / Intel (x86_64)" },
              { value: "aarch64", label: "ARM64 / Apple Silicon (aarch64)" },
            ]}
          />
        </label>
        <label>
          {meter ? "统计程序" : "安装包"}（不超过 {maximum} MiB）
          <input
            key={target}
            type="file"
            aria-label="安装包"
            disabled={busy}
            accept={
              target === "windows"
                ? ".exe"
                : target === "darwin"
                  ? ".gz"
                  : ".AppImage"
            }
            style={{ display: "block", marginTop: 8, width: "100%" }}
            onChange={(event) => {
              reset();
              setFile(event.target.files?.[0]);
            }}
          />
        </label>
        <label>
          对应 .sig 签名文件
          <input
            type="file"
            aria-label="签名文件"
            accept=".sig"
            disabled={busy}
            style={{ display: "block", marginTop: 8, width: "100%" }}
            onChange={(event) => {
              reset();
              setSignature(event.target.files?.[0]);
            }}
          />
        </label>
        {meter && (
          <Typography.Text type="secondary">
            请使用统计程序专用发布密钥生成的签名。发布后，统计服务会独立静默强制更新。
          </Typography.Text>
        )}
      </Space>
    </Modal>
  );
}
