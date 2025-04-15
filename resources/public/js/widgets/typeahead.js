export function TypeaheadSearch(inputId, containerId) {
  const searchPhrase = document.getElementById(inputId);
  const container = document.getElementById(containerId);
  if (!searchPhrase) return;
  if (!container) return;

  if (searchPhrase.hasAttribute("data-history-bound")) return;
  searchPhrase.setAttribute("data-history-bound", "true");
  window.addEventListener("popstate", (event) => {
    // the current URL already reflects the desired state.
    const currentUrl = new URL(window.location.href);
    const searchPhraseValue = currentUrl.searchParams.get("phrase") || "";
    searchPhrase.value = searchPhraseValue;
    const sections = currentUrl.searchParams.get("sections") || 0;

    const historyChangeEvent = new CustomEvent("historychange", {
      detail: {
        phrase: searchPhrase.value,
        sections: sections,
      },
    });

    searchPhrase.dispatchEvent(historyChangeEvent);
  });
  container.addEventListener("historychange", (e) => {
    searchPhrase.focus();
  });
}
