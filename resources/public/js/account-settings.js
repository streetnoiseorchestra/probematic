(() => {
  const appearanceKey = "streetnoise.appearance";
  const appearanceValues = new Set(["light", "dark", "system"]);
  const colorScheme = window.matchMedia("(prefers-color-scheme: dark)");

  const normalizeAppearance = (value) =>
    appearanceValues.has(value) ? value : "system";

  const storedAppearance = () => {
    try {
      return normalizeAppearance(window.localStorage.getItem(appearanceKey));
    } catch (_error) {
      return "system";
    }
  };

  const applyAppearance = (value) => {
    const appearance = normalizeAppearance(value);
    const dark = appearance === "dark" || (appearance === "system" && colorScheme.matches);
    document.documentElement.dataset.accountAppearance = appearance;
    document.documentElement.classList.toggle("wa-dark", dark);
    document.documentElement.classList.toggle("wa-light", !dark);
    document
      .querySelectorAll('input[name="appearance"]')
      .forEach((input) => (input.checked = input.value === appearance));
    return appearance;
  };

  window.StreetnoiseAppearance = {
    apply: applyAppearance,
    get: storedAppearance,
    set(value) {
      const appearance = applyAppearance(value);
      try {
        window.localStorage.setItem(appearanceKey, appearance);
      } catch (_error) {
        // The visual selection still works for this page when storage is blocked.
      }
    },
    sync() {
      applyAppearance(storedAppearance());
    },
  };

  colorScheme.addEventListener("change", () => {
    if (storedAppearance() === "system") applyAppearance("system");
  });

  window.addEventListener("storage", (event) => {
    if (event.key === appearanceKey) applyAppearance(event.newValue);
  });

  const objectUrls = new WeakMap();
  const avatarPreview = () => document.getElementById("account-profile-avatar-preview");

  const revokeAvatarUrl = (preview) => {
    const objectUrl = objectUrls.get(preview);
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrls.delete(preview);
  };

  window.StreetnoiseAccountAvatar = {
    stage(event, profile) {
      const file = event.target.files && event.target.files[0];
      if (!file) return false;
      const preview = avatarPreview();
      if (preview) {
        revokeAvatarUrl(preview);
        const objectUrl = URL.createObjectURL(file);
        objectUrls.set(preview, objectUrl);
        preview.src = objectUrl;
        preview.classList.add("staged");
      }
      profile.avatar = {
        filename: file.name,
        "mime-type": file.type,
        size: file.size,
      };
      return true;
    },
    remove(profile) {
      const preview = avatarPreview();
      if (preview) {
        revokeAvatarUrl(preview);
        preview.removeAttribute("src");
        preview.classList.remove("staged");
      }
      profile.avatar = null;
    },
  };

  window.StreetnoiseAccountPreferences = {
    syncTimeZone(select, preferences) {
      const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      if (zone && Array.from(select.options).some((option) => option.value === zone)) {
        preferences["time-zone"] = zone;
        select.value = zone;
      }
    },
  };
})();
