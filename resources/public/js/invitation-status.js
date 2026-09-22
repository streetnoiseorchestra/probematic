(() => {
  let redirectTimer;

  function clearRedirect() {
    window.clearTimeout(redirectTimer);
    redirectTimer = undefined;
  }

  function show(element, state) {
    clearRedirect();
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const spinner = element.querySelector('wa-spinner');
    if (spinner) spinner.style.setProperty('--speed', reducedMotion ? '0s' : '1.6s');
    if (state !== 'accepted') return;

    const mark = element.querySelector('#invitation-success-mark');
    if (!reducedMotion && mark?.animate) {
      mark.animate([
        { transform: 'scale(0.65)', opacity: 0 },
        { transform: 'scale(1.12)', opacity: 1, offset: 0.7 },
        { transform: 'scale(1)', opacity: 1 }
      ], { duration: 450, easing: 'cubic-bezier(.18,.8,.22,1)' });
      element.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 180 });
    }

    redirectTimer = window.setTimeout(() => {
      const link = element.querySelector('#invitation-continue');
      if (link && element.isConnected) window.location.assign(link.href);
    }, 3000);
  }

  window.addEventListener('pagehide', clearRedirect);
  window.StreetnoiseInvitationStatus = { show };
})();
