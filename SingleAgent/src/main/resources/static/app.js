const chatForm = document.querySelector("#chatForm");
const promptInput = document.querySelector("#promptInput");
const messages = document.querySelector("#messages");
const emptyState = document.querySelector("#emptyState");
const threadInput = document.querySelector("#threadId");
const conversationInput = document.querySelector("#conversationId");
const sendBtn = document.querySelector("#sendBtn");
const stopBtn = document.querySelector("#stopBtn");
const clearBtn = document.querySelector("#clearBtn");
const newSessionBtn = document.querySelector("#newSessionBtn");
const skillUploadForm = document.querySelector("#skillUploadForm");
const skillZipInput = document.querySelector("#skillZipInput");
const skillFileName = document.querySelector("#skillFileName");
const skillUploadBtn = document.querySelector("#skillUploadBtn");
const skillUploadStatus = document.querySelector("#skillUploadStatus");
const statusDot = document.querySelector("#statusDot");
const statusText = document.querySelector("#statusText");

const storageKey = "ai-coding-helper-session";
let abortController = null;

function createId(prefix) {
  const random = Math.random().toString(36).slice(2, 8);
  return `${prefix}-${Date.now().toString(36)}-${random}`;
}

function loadSession() {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) || "{}");
    threadInput.value = saved.threadId || createId("thread");
    conversationInput.value = saved.conversationId || createId("conversation");
  } catch {
    threadInput.value = createId("thread");
    conversationInput.value = createId("conversation");
  }
}

function saveSession() {
  localStorage.setItem(storageKey, JSON.stringify({
    threadId: threadInput.value.trim(),
    conversationId: conversationInput.value.trim()
  }));
}

function setStatus(type, text) {
  statusDot.classList.toggle("busy", type === "busy");
  statusDot.classList.toggle("error", type === "error");
  statusText.textContent = text;
}

function setBusy(isBusy) {
  sendBtn.disabled = isBusy;
  stopBtn.disabled = !isBusy;
  promptInput.disabled = isBusy;
}

function scrollToBottom() {
  messages.scrollTop = messages.scrollHeight;
}

function nowLabel() {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date());
}

function addMessage(role, text = "") {
  emptyState?.remove();

  const wrapper = document.createElement("article");
  wrapper.className = `message ${role}`;

  const meta = document.createElement("div");
  meta.className = "message-meta";
  meta.textContent = `${role === "user" ? "你" : role === "error" ? "错误" : "Agent"} · ${nowLabel()}`;

  const bubble = document.createElement("div");
  bubble.className = `bubble${text ? "" : " empty"}`;

  const waitBadge = document.createElement("div");
  waitBadge.className = "bubble-wait";
  waitBadge.hidden = true;

  const waitSpinner = document.createElement("span");
  waitSpinner.className = "bubble-wait-spinner";
  waitSpinner.setAttribute("aria-hidden", "true");

  const waitLabel = document.createElement("span");
  waitLabel.textContent = "思考中";

  const waitTime = document.createElement("span");
  waitTime.className = "bubble-wait-time";
  waitTime.textContent = "0.0s";

  waitBadge.append(waitSpinner, waitLabel, waitTime);

  const contentElement = document.createElement("span");
  contentElement.className = "bubble-content";
  contentElement.textContent = text;

  const activity = document.createElement("span");
  activity.className = "bubble-activity";
  activity.hidden = role !== "assistant";
  activity.setAttribute("aria-label", "正在思考");
  activity.innerHTML = "<span></span><span></span><span></span>";

  bubble.append(contentElement);
  if (role === "assistant") {
    bubble.append(activity);
  }
  wrapper.append(meta);
  if (role === "assistant") {
    wrapper.append(waitBadge);
  }
  wrapper.append(bubble);
  messages.append(wrapper);
  scrollToBottom();

  let isStreaming = false;
  let thinkingTimer = null;
  let waitStartedAt = 0;
  let waitTimer = null;

  function formatWaitElapsed(milliseconds) {
    const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;

    if (hours > 0) {
      return `${hours}时${minutes}分${seconds}秒`;
    }

    if (minutes > 0) {
      return `${minutes}分${seconds}秒`;
    }

    if (milliseconds < 10_000) {
      return `${Math.max(0, milliseconds / 1000).toFixed(1)}秒`;
    }

    return `${totalSeconds}秒`;
  }

  function updateWaitTime() {
    waitTime.textContent = formatWaitElapsed(Date.now() - waitStartedAt);
  }

  function finishWaiting(label = "耗费时间") {
    if (waitTimer) {
      window.clearInterval(waitTimer);
      waitTimer = null;
    }
    if (role !== "assistant" || !waitStartedAt) {
      return;
    }
    updateWaitTime();
    waitLabel.textContent = label;
    waitBadge.hidden = false;
    waitBadge.classList.add("done");
  }

  function startWaiting() {
    if (role !== "assistant") {
      return;
    }
    if (waitTimer) {
      window.clearInterval(waitTimer);
      waitTimer = null;
    }
    waitStartedAt = Date.now();
    waitLabel.textContent = "思考中";
    waitBadge.classList.remove("done");
    updateWaitTime();
    waitBadge.hidden = false;
    waitTimer = window.setInterval(updateWaitTime, 100);
  }

  function clearThinkingTimer() {
    if (thinkingTimer) {
      window.clearTimeout(thinkingTimer);
      thinkingTimer = null;
    }
  }

  function showActivity(isVisible) {
    if (!activity) {
      return;
    }
    activity.hidden = !isVisible;
    activity.classList.toggle("active", isVisible);
  }

  function scheduleThinking() {
    clearThinkingTimer();
    if (!isStreaming || role !== "assistant") {
      return;
    }
    thinkingTimer = window.setTimeout(() => {
      showActivity(true);
      scrollToBottom();
    }, 650);
  }

  return {
    wrapper,
    bubble,
    activity,
    append(value) {
      if (!value) {
        return;
      }
      bubble.classList.remove("empty");
      showActivity(false);
      contentElement.textContent += value;
      scheduleThinking();
      scrollToBottom();
    },
    set(value) {
      bubble.classList.toggle("empty", !value);
      contentElement.textContent = value;
      showActivity(isStreaming && !value);
      scrollToBottom();
    },
    setStreaming(streaming) {
      clearThinkingTimer();
      isStreaming = Boolean(streaming);
      if (!isStreaming) {
        finishWaiting();
        showActivity(false);
      } else if (contentElement.textContent) {
        showActivity(false);
        scheduleThinking();
      } else {
        startWaiting();
        showActivity(true);
      }
      scrollToBottom();
    },
    getText() {
      return contentElement.textContent;
    }
  };
}

function autoResizeTextarea() {
  promptInput.style.height = "auto";
  promptInput.style.height = `${Math.min(promptInput.scrollHeight, 180)}px`;
}

function setUploadStatus(text, type = "") {
  skillUploadStatus.textContent = text;
  skillUploadStatus.classList.toggle("success", type === "success");
  skillUploadStatus.classList.toggle("error", type === "error");
}

function getSelectedSkillFile() {
  return skillZipInput.files && skillZipInput.files.length > 0
    ? skillZipInput.files[0]
    : null;
}

async function uploadSkillZip(file) {
  const formData = new FormData();
  formData.append("file", file);

  skillUploadBtn.disabled = true;
  skillZipInput.disabled = true;
  setUploadStatus("上传中...");

  try {
    const response = await fetch("/skill/upload", {
      method: "POST",
      body: formData
    });
    const result = (await response.text()).trim();

    if (!response.ok || result !== "success") {
      throw new Error(result || `上传失败：HTTP ${response.status}`);
    }

    setUploadStatus("上传成功，后端已解压 skill。", "success");
  } catch (error) {
    setUploadStatus(error.message || "上传失败", "error");
  } finally {
    skillZipInput.disabled = false;
    skillUploadBtn.disabled = !getSelectedSkillFile();
  }
}

async function readStream(response, assistantMessage) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  const contentType = response.headers.get("content-type") || "";
  const isEventStream = contentType.includes("text/event-stream");
  let sseBuffer = "";

  function appendSseEvent(eventText) {
    const data = eventText
      .split("\n")
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).replace(/^ /, ""))
      .filter((line) => line && line !== "[DONE]")
      .join("");

    if (data) {
      assistantMessage.append(data);
    }
  }

  function appendSseChunk(chunk, flush = false) {
    sseBuffer += chunk.replace(/\r\n/g, "\n");

    while (true) {
      const eventEnd = sseBuffer.indexOf("\n\n");
      if (eventEnd < 0) {
        break;
      }

      const eventText = sseBuffer.slice(0, eventEnd);
      sseBuffer = sseBuffer.slice(eventEnd + 2);
      appendSseEvent(eventText);
    }

    if (flush && sseBuffer.trim()) {
      appendSseEvent(sseBuffer);
      sseBuffer = "";
    }
  }

  function normalizePlainChunk(chunk) {
    return chunk
      .replace(/\[DONE\]/g, "")
      .replace(/(^|\s)data:\s?/g, "$1");
  }

  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }

    const chunk = decoder.decode(value, { stream: true });
    if (isEventStream) {
      appendSseChunk(chunk);
    } else {
      assistantMessage.append(normalizePlainChunk(chunk));
    }
  }

  const rest = decoder.decode();
  if (isEventStream || sseBuffer) {
    appendSseChunk(rest, true);
  } else if (rest) {
    assistantMessage.append(normalizePlainChunk(rest));
  }
}

async function sendMessage(content) {
  const threadId = threadInput.value.trim() || createId("thread");
  const conversationId = conversationInput.value.trim() || createId("conversation");
  threadInput.value = threadId;
  conversationInput.value = conversationId;
  saveSession();

  addMessage("user", content);
  const assistantMessage = addMessage("assistant");
  assistantMessage.setStreaming(true);
  abortController = new AbortController();
  setBusy(true);
  setStatus("busy", "生成中");

  try {
    const response = await fetch("/agent/chatStream", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "text/plain"
      },
      body: JSON.stringify({ threadId, conversationId, content }),
      signal: abortController.signal
    });

    if (!response.ok || !response.body) {
      throw new Error(`请求失败：HTTP ${response.status}`);
    }

    await readStream(response, assistantMessage);

    if (!assistantMessage.getText().trim()) {
      assistantMessage.set("本次没有收到内容。");
    }
    assistantMessage.setStreaming(false);
    setStatus("ready", "就绪");
  } catch (error) {
    if (error.name === "AbortError") {
      assistantMessage.setStreaming(false);
      assistantMessage.append("\n\n已停止生成。");
      setStatus("ready", "已停止");
      return;
    }

    assistantMessage.wrapper.remove();
    addMessage("error", error.message || "请求发生异常");
    setStatus("error", "请求失败");
  } finally {
    abortController = null;
    setBusy(false);
    promptInput.focus();
  }
}

chatForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const content = promptInput.value.trim();
  if (!content || abortController) {
    return;
  }

  promptInput.value = "";
  autoResizeTextarea();
  sendMessage(content);
});

promptInput.addEventListener("input", autoResizeTextarea);

promptInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    chatForm.requestSubmit();
  }
});

stopBtn.addEventListener("click", () => {
  abortController?.abort();
});

clearBtn.addEventListener("click", () => {
  messages.innerHTML = "";
  messages.append(emptyState);
  setStatus("ready", "就绪");
});

newSessionBtn.addEventListener("click", () => {
  threadInput.value = createId("thread");
  conversationInput.value = createId("conversation");
  saveSession();
  clearBtn.click();
  promptInput.focus();
});

skillZipInput.addEventListener("change", () => {
  const file = getSelectedSkillFile();
  if (!file) {
    skillFileName.textContent = "选择 skill 压缩包";
    skillUploadBtn.disabled = true;
    setUploadStatus("仅支持 .zip 文件");
    return;
  }

  skillFileName.textContent = file.name;
  if (!file.name.toLowerCase().endsWith(".zip")) {
    skillUploadBtn.disabled = true;
    setUploadStatus("请选择 .zip 文件", "error");
    return;
  }

  skillUploadBtn.disabled = false;
  setUploadStatus(`已选择：${file.name}`);
});

skillUploadForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const file = getSelectedSkillFile();
  if (!file) {
    setUploadStatus("请先选择 .zip 文件", "error");
    return;
  }
  if (!file.name.toLowerCase().endsWith(".zip")) {
    setUploadStatus("请选择 .zip 文件", "error");
    return;
  }
  uploadSkillZip(file);
});

threadInput.addEventListener("change", saveSession);
conversationInput.addEventListener("change", saveSession);

loadSession();
autoResizeTextarea();
