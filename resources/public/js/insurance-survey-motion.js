let unlockTimer;
const cardThrowDuration = 1000;
const deckAdvanceDuration = 500;

function submit(transitionKind, post) {
  const reducedMotion = window.matchMedia(
    "(prefers-reduced-motion: reduce)",
  ).matches;

  if (transitionKind !== "item" || reducedMotion) {
    post();
    return;
  }

  const card = document.querySelector(".insurance-survey-card-current");
  const question = document.querySelector(".insurance-survey-question");

  card?.classList.remove("is-survey-throwing");
  question?.classList.remove("is-submitting-item");
  void card?.offsetWidth;
  card?.classList.add("is-survey-throwing");
  question?.classList.add("is-submitting-item");
  window.setTimeout(() => {
    const progress = document.querySelector("#insurance-survey-progress");
    const nextProgress = Number(progress?.dataset.nextValue);

    if (progress && Number.isFinite(nextProgress)) {
      progress.style.setProperty(
        "--insurance-survey-progress-value",
        `${nextProgress}%`,
      );
      progress.value = nextProgress;
    }

    document
      .querySelectorAll(".insurance-survey-card-layer")
      .forEach((layer) => layer.classList.add("is-survey-advancing"));
  }, cardThrowDuration);
  window.setTimeout(post, cardThrowDuration + deckAdvanceDuration);
}

function unlockQuestion() {
  window.clearTimeout(unlockTimer);
  unlockTimer = undefined;

  const form = document.querySelector(
    "#insurance-survey-question [data-id='insurance-survey-question']",
  );

  if (!form) {
    return;
  }

  form.inert = false;
  form.removeAttribute("data-survey-motion-locked");
  form.querySelectorAll("[data-survey-motion-disabled]").forEach((button) => {
    button.disabled = false;
    button.removeAttribute("data-survey-motion-disabled");
  });
}

function lockQuestionForTransition() {
  const workflow = document.querySelector(".insurance-survey-workflow");
  const form = workflow?.querySelector(
    "#insurance-survey-question [data-id='insurance-survey-question']",
  );

  if (
    !form ||
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  ) {
    return;
  }

  const duration =
    workflow.dataset.transitionKind === "item" ? deckAdvanceDuration : 650;

  window.clearTimeout(unlockTimer);
  form.inert = true;
  form.setAttribute("data-survey-motion-locked", "true");
  form.querySelectorAll("button:not(:disabled)").forEach((button) => {
    button.disabled = true;
    button.setAttribute("data-survey-motion-disabled", "true");
  });
  unlockTimer = window.setTimeout(unlockQuestion, duration);
}

document.addEventListener("datastar-fetch", (event) => {
  const { el, type } = event.detail;

  if (
    type === "finished" &&
    el?.dataset.id === "insurance-survey-question"
  ) {
    window.requestAnimationFrame(lockQuestionForTransition);
  }
});

window.addEventListener("pageshow", () => {
  unlockQuestion();
});

window.InsuranceSurveyMotion = { submit };
