import confetti from "confetti";

const particleCount = 200;
const defaults = {
  disableForReducedMotion: true,
  origin: { y: 0.3 },
  resize: true,
  useWorker: true,
};

function fire(particleRatio, options) {
  confetti({
    ...defaults,
    ...options,
    particleCount: Math.floor(particleCount * particleRatio),
  });
}

export default function celebrate() {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    return;
  }

  if (navigator.vibrate) {
    navigator.vibrate(100);
  }

  fire(0.25, { spread: 26, startVelocity: 55 });
  fire(0.2, { spread: 60 });
  fire(0.35, { decay: 0.91, scalar: 0.8, spread: 100 });
  fire(0.1, { decay: 0.92, scalar: 1.2, spread: 120, startVelocity: 25 });
  fire(0.1, { spread: 120, startVelocity: 45 });
}
