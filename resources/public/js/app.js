import { $, listen } from "./utils.js?v3";
import {
  Dropdown,
  DiscoverDropdowns,
} from "./widgets/attendance-dropdown.js?v3";
import {
  ActionMenu2All,
  ActionMenu,
  Flyout,
} from "./widgets/action-menu.js?v3";

import { DiscoverTooltips } from "./widgets/tooltip.js?v3";

import { TypeaheadSearch } from "./widgets/typeahead.js?v3";

import { ActionMenuPopover } from "./widgets/popover.js?v3";

import { MyDialog } from "./widgets/dialog.js";

window.ActionMenuPopover = ActionMenuPopover;
window.TypeaheadSearch = TypeaheadSearch;

//// SETUP
htmx.onLoad(function (content) {
  htmx.config.defaultSettleDelay = 0;
  htmx.config.defaultSwapStyle = "outerHTML";
});

function initWidgets(evt) {
  if (evt && evt.target) {
    DiscoverDropdowns(evt.target);
    InitializeMarkdownEditors(evt.target);
    ActionMenu2All(evt.target);
    DiscoverTooltips(evt.target);
  } else {
    DiscoverDropdowns();
    ActionMenu2All();
    InitializeMarkdownEditors();
    listen("click", "[data-flyout-trigger]", Flyout);
    DiscoverTooltips();
  }

  listen("click", "[data-action-menu-trigger]", ActionMenu);
  AutoSizeTextAreas();
  try {
    Dropzone.discover();
  } catch (e) {
    console.error("Dropzone not found");
  }
}

document.body.addEventListener("htmx:beforeSwap", function (evt) {
  if (evt.detail.xhr.status === 404) {
    evt.detail.shouldSwap = true;
  } else if (evt.detail.xhr.status === 400) {
    evt.detail.shouldSwap = true;
  } else if (evt.detail.xhr.status === 422) {
    // allow 422 responses to swap as we are using this as a signal that
    // a form was submitted with bad data and want to rerender with the
    // errors
    //
    // set isError to false to avoid error logging in console
    evt.detail.shouldSwap = true;
    evt.detail.isError = false;
  }
});

document.addEventListener("datastar-sse", function (evt) {
  const { elId, type } = evt.detail;
  if (elId === "long-lived-sse") return;
  if (type === "started") {
    NProgress.start();
  } else if (type === "finished") {
    NProgress.done();
    setTimeout(() => NProgress.remove(), 1000);
  } else if (type === "error") {
    NProgress.done();
    NProgress.remove();
  } else if (type === "retries-failed") {
    NProgress.done();
    NProgress.remove();
  }
});

document.body.addEventListener("htmx:beforeRequest", function (evt) {
  //console.log("before request", evt);
  NProgress.start();
});

document.body.addEventListener("htmx:beforeSwap", function (evt) {
  NProgress.inc(0.9);
});

document.body.addEventListener("htmx:afterRequest", function (evt) {
  //console.log("htmx:afterRequest init", evt);
  NProgress.done();
  setTimeout(() => NProgress.remove(), 1000);
});

document.body.addEventListener("htmx:afterSwap", function (evt) {
  //console.log("after swap", evt);
});

document.body.addEventListener("htmx:afterSettle", function (evt) {
  try {
    //console.log("htmx:afterSettle init", evt);
    initWidgets(evt);
  } catch (err) {
    console.error(err);
  }
});

document.body.addEventListener("htmx:responseError", function (evt) {
  NProgress.done();
  NProgress.remove();
});

document.body.addEventListener("htmx:sendError", function (evt) {
  NProgress.done();
  NProgress.remove();
});

document.addEventListener("DOMContentLoaded", function () {
  initWidgets();
});

document.addEventListener("newDropdown", function (evt) {
  Dropdown(document.querySelector(evt.detail.value));
});

function checkboxLimitReached(event) {
  const checkedChecks = document.querySelectorAll(
    "[data-checkbox-limit]:checked",
  );
  if (checkedChecks.length == 0) return false;
  const maximum = parseInt(checkedChecks[0].getAttribute("data-maximum"));
  if (checkedChecks.length >= maximum + 1) {
    // console.log("max reached");
    return true;
  }
  return false;
}

window.checkboxLimitReached = checkboxLimitReached;

function pageDirtyHandler(event) {
  event.returnValue =
    "Are you sure you want to leave? You have unsaved changes.";
}

function setPageDirty() {
  document.addEventListener("beforeunload", pageDirtyHandler);
}

function unsetPageDirty() {
  document.removeEventListener("beforeunload", pageDirtyHandler);
}

function AutoSizeTextAreas() {
  function onTextAreaInput() {
    this.style.height = 0;
    this.style.height = this.scrollHeight + "px";
  }

  const txs = document.querySelectorAll("textarea[data-auto-size]");
  for (let i = 0; i < txs.length; i++) {
    txs[i].setAttribute(
      "style",
      "height:" + txs[i].scrollHeight + "px;overflow-y:hidden;",
    );
    txs[i].addEventListener("input", onTextAreaInput, false);
  }
}

document.addEventListener("setPageDirty", function (evt) {
  setPageDirty();
});

function MarkdownEditor(target) {
  const imageUploadEndpoint = target.getAttribute("data-image-upload-endpoint");
  const hasUpload = !!imageUploadEndpoint;
  const maxSizeMB = 10;
  const maxSizeB = 1024 * 1024 * maxSizeMB;
  const easyMDE = new EasyMDE({
    element: target,
    spellChecker: false,
    forceSync: true,
    promptURLs: true,
    uploadImage: hasUpload,
    autoDownloadFontAwesome: false,
    previewImagesInEditor: true,
    toolbar: [
      "bold",
      "italic",
      "strikethrough",
      "heading",
      "|",
      "quote",
      "code",
      "unordered-list",
      "ordered-list",
      "clean-block",
      "|",
      "link",
      "upload-image",
      "table",
      "horizontal-rule",
      "|",
      "preview",
      "side-by-side",
      "fullscreen",
    ],
    imageUploadFunction: (file, onSuccess, onError) => {
      if (file.size > maxSizeB) {
        onError(`File is too big! Maximum size is ${maxSizeMB}MB.`);
        return;
      }

      const validMimeTypes = [
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/jpg",
      ];
      if (!validMimeTypes.includes(file.type)) {
        onError("Invalid file type. Only JPG, PNG, and GIF files are allowed.");
        return;
      }

      let formData = new FormData();
      formData.append("file", file);

      let xhr = new XMLHttpRequest();

      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) return;
        // console.log(xhr);
        if (xhr.status === 201) {
          const response = JSON.parse(xhr.responseText);
          onSuccess(response["file-url"]);
        } else {
          const response = JSON.parse(xhr.responseText);
          onError(response.error);
        }
      };

      xhr.onerror = function () {
        onError("XMLHttpRequest error.");
      };

      xhr.open("POST", imageUploadEndpoint, true);
      xhr.send(formData);
    },
  });
  return easyMDE;
}

function InitializeMarkdownEditors(target) {
  if (target) {
    target.querySelectorAll(".markdown-editor").forEach(MarkdownEditor);
  } else {
    document.querySelectorAll(".markdown-editor").forEach(MarkdownEditor);
  }
}

function toggleSidebar() {
  // store the sidebar state in local storage
  // read the value, invert it, and set it again
  const appSidebarCollapsed = localStorage.getItem("appSidebarCollapsed");
  const newState =
    !appSidebarCollapsed || appSidebarCollapsed === "true" ? "false" : "true";
  localStorage.setItem("appSidebarCollapsed", newState);
}

document.addEventListener("DOMContentLoaded", function (evt) {
  // initialize appSidebarCollapsed
  if (localStorage.getItem("appSidebarCollapsed") === null) {
    localStorage.setItem("appSidebarCollapsed", "false");
  }
});
document.addEventListener("appSidebarToggled", function (evt) {
  // console.log("sidebar toggled");
  toggleSidebar();
});
