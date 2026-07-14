import celebrate from "./confetti.js";

const rootSelector = "[data-insurance-survey-celebration]";
const autoCelebratedKeys = new Set();

function storageGet(key) {
  try {
    return window.localStorage.getItem(key);
  } catch (_error) {
    return null;
  }
}

function storageSet(key, value) {
  try {
    window.localStorage.setItem(key, value);
  } catch (_error) {
    // The celebration still works when storage is unavailable.
  }
}

function revealStage(root, stageNumber) {
  const stage = root.querySelector(`[data-celebration-stage="${stageNumber}"]`);

  if (!stage) {
    return;
  }

  stage.hidden = false;
  requestAnimationFrame(() => stage.classList.add("revealed"));
}

function revealStageAfter(root, stageNumber) {
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  window.setTimeout(
    () => revealStage(root, stageNumber),
    reducedMotion ? 0 : 250,
  );
}

function prepare(root) {
  if (root.dataset.celebrationReady === "true") {
    return;
  }

  root.dataset.celebrationReady = "true";

  const key = root.dataset.celebrationKey;
  const button = root.querySelector("[data-celebration-button]");
  let clickCount = storageGet(key) === "complete" ? 3 : 0;

  if (clickCount === 3) {
    revealStage(root, 1);
    revealStage(root, 2);
    revealStage(root, 3);
  }

  if (
    root.dataset.celebrateAutomatically === "true" &&
    !autoCelebratedKeys.has(key)
  ) {
    autoCelebratedKeys.add(key);
    window.setTimeout(celebrate, 200);
  }

  button?.addEventListener("click", () => {
    celebrate();
    clickCount = Math.min(clickCount + 1, 3);
    revealStageAfter(root, clickCount);

    if (clickCount === 3) {
      storageSet(key, "complete");
    }
  });
}

function discover() {
  document.querySelectorAll(rootSelector).forEach(prepare);
}

new MutationObserver(discover).observe(document.documentElement, {
  childList: true,
  subtree: true,
});

window.addEventListener("pageshow", discover);
discover();
