const labSelector = "[data-insurance-survey-animation-lab]";
const labState = new WeakMap();

function stateFor(lab) {
  if (!labState.has(lab)) {
    labState.set(lab, { timers: [] });
  }

  return labState.get(lab);
}

function schedule(lab, callback, delay) {
  stateFor(lab).timers.push(window.setTimeout(callback, delay));
}

function restartClass(element, className) {
  if (!element) {
    return;
  }

  element.classList.remove(className);
  void element.offsetWidth;
  element.classList.add(className);
}

function reset(lab) {
  const workflow = lab.closest(".insurance-survey-workflow");
  const state = stateFor(lab);

  state.timers.forEach((timer) => window.clearTimeout(timer));
  state.timers = [];
  workflow?.style.removeProperty(
    "--insurance-survey-debug-deck-forward-delay",
  );

  workflow
    ?.querySelector(".insurance-survey-card-current")
    ?.classList.remove("is-debug-throwing", "is-debug-dismissed");
  workflow
    ?.querySelector(".insurance-survey-question")
    ?.classList.remove(
      "is-debug-transitioning",
      "is-debug-item-transitioning",
    );
  workflow
    ?.querySelectorAll(".insurance-survey-card-layer")
    ?.forEach((layer) => layer.classList.remove("is-debug-advancing"));
  workflow
    ?.querySelectorAll(".insurance-survey-debug-milestone")
    ?.forEach((element) => element.remove());
}

function throwCard(lab) {
  restartClass(
    lab
      .closest(".insurance-survey-workflow")
      ?.querySelector(".insurance-survey-card-current"),
    "is-debug-throwing",
  );
}

function advanceDeck(lab) {
  const workflow = lab.closest(".insurance-survey-workflow");
  const current = workflow?.querySelector(".insurance-survey-card-current");

  if (!current?.classList.contains("is-debug-throwing")) {
    current?.classList.add("is-debug-dismissed");
  }

  workflow
    ?.querySelectorAll(".insurance-survey-card-layer")
    ?.forEach((layer) => restartClass(layer, "is-debug-advancing"));
}

function transitionQuestion(lab, itemTransition = false) {
  restartClass(
    lab
      .closest(".insurance-survey-workflow")
      ?.querySelector(".insurance-survey-question"),
    itemTransition ? "is-debug-item-transitioning" : "is-debug-transitioning",
  );
}

function showEncouragement(lab) {
  const workflow = lab.closest(".insurance-survey-workflow");
  const deck = workflow?.querySelector(".insurance-survey-card-deck");
  const template = lab.querySelector("[data-animation-lab-milestone]");

  if (!deck || !template) {
    return;
  }

  deck.querySelector(".insurance-survey-debug-milestone")?.remove();

  const fragment = template.content.cloneNode(true);
  const milestone = fragment.querySelector(".insurance-survey-milestone");
  milestone?.classList.add("insurance-survey-debug-milestone");
  deck.append(fragment);
}

function runSequence(lab) {
  reset(lab);
  lab
    .closest(".insurance-survey-workflow")
    ?.style.setProperty(
      "--insurance-survey-debug-deck-forward-delay",
      "1000ms",
    );
  throwCard(lab);
  advanceDeck(lab);
  transitionQuestion(lab, true);
  schedule(lab, () => showEncouragement(lab), 1550);
}

function prepare(lab) {
  if (lab.dataset.animationLabReady === "true") {
    return;
  }

  lab.dataset.animationLabReady = "true";
  lab.addEventListener("click", (event) => {
    const button = event.target.closest("[data-animation-lab-action]");

    if (!button) {
      return;
    }

    switch (button.dataset.animationLabAction) {
      case "sequence":
        runSequence(lab);
        break;
      case "throw":
        reset(lab);
        throwCard(lab);
        break;
      case "advance":
        advanceDeck(lab);
        break;
      case "question":
        reset(lab);
        transitionQuestion(lab);
        break;
      case "encouragement":
        reset(lab);
        showEncouragement(lab);
        break;
      case "reset":
        reset(lab);
        break;
    }
  });
}

function discover() {
  document.querySelectorAll(labSelector).forEach(prepare);
}

new MutationObserver(discover).observe(document.documentElement, {
  childList: true,
  subtree: true,
});

window.addEventListener("pageshow", discover);
discover();
