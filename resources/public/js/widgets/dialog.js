/**
 * MyDialog - A simple headless dialog web component
 *
 * Features:
 *
 * 1. HTML Properties:
 *    - open: Boolean - Controls dialog visibility. Default: false
 *    - light-dismiss: Boolean - Enables closing when clicking outside. Default: false
 *    - aria-labelledby: String - ID reference for accessible dialog title
 *    - aria-describedby: String - ID reference for accessible dialog description
 *    - aria-modal: String - Indicates if dialog is modal. Default: "true"
 *
 * 2. Methods:
 *    - show() - Opens the dialog
 *    - hide() - Closes the dialog
 *
 * 3. Events:
 *    - my-show - Fired when dialog opens (can be prevented)
 *    - my-after-show - Fired after dialog opens and animations complete
 *    - my-hide - Fired when dialog is requested to close (can be prevented)
 *    - my-after-hide - Fired after dialog closes and animations complete
 *
 * 4. Special Features:
 *    - data-dialog="close" - Add to any element inside dialog to make it close the dialog
 *    - Pulse animation when trying to click outside a non-light-dismiss dialog
 *    - Focus management - Returns focus to trigger element when closed
 *    - Escape key support - Closes dialog when Escape is pressed
 *    - Accessibility - Full support for ARIA attributes and keyboard interaction
 *
 * Usage Example:
 *
 * <my-dialog aria-labelledby="dialog-title" aria-describedby="dialog-description">
 *   <div class="dialog-content">
 *     <h2 id="dialog-title">Dialog Title</h2>
 *     <div id="dialog-description">
 *       <p>Dialog content goes here</p>
 *     </div>
 *     <button data-dialog="close">Close</button>
 *   </div>
 * </my-dialog>
 */

import { LitElement, html, css } from "lit-core.js";

export class MyDialog extends LitElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    lightDismiss: { type: Boolean, attribute: "light-dismiss" },
    ariaLabelledby: {
      type: String,
      attribute: "aria-labelledby",
      reflect: true,
    },
    ariaDescribedby: {
      type: String,
      attribute: "aria-describedby",
      reflect: true,
    },
    ariaModal: { type: String, attribute: "aria-modal", reflect: true },
  };

  static styles = css`
    :host {
      --show-duration: 200ms;
      --hide-duration: 200ms;
      display: none;
    }

    :host([open]) {
      display: block;
    }

    dialog {
      border: none;
      background: none;
      padding: 0;
      margin: 0;
      width: auto;
      height: auto;
      overflow: visible;
    }

    dialog::backdrop {
      background-color: rgba(0, 0, 0, 0.25);
    }

    dialog.pulse {
      animation: pulse 250ms ease;
    }

    @keyframes pulse {
      0% {
        transform: scale(1);
      }
      50% {
        transform: scale(1.02);
      }
      100% {
        transform: scale(1);
      }
    }
  `;

  constructor() {
    super();
    this.open = false;
    this.lightDismiss = false;
    this.ariaModal = "true"; // Default to true for modal dialogs
    this.handleDocumentKeyDown = this.handleDocumentKeyDown.bind(this);
    this.handleDialogClick = this.handleDialogClick.bind(this);
  }

  firstUpdated() {
    this.dialog = this.shadowRoot.querySelector("dialog");
    if (this.open) {
      this.showDialog();
    }
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.removeOpenListeners();
  }

  updated(changedProperties) {
    if (changedProperties.has("open")) {
      if (this.open && !this.dialog.open) {
        this.showDialog();
      } else if (!this.open && this.dialog.open) {
        this.hideDialog();
      }
    }

    // Update ARIA attributes when they change
    if (
      changedProperties.has("ariaLabelledby") ||
      changedProperties.has("ariaDescribedby") ||
      changedProperties.has("ariaModal")
    ) {
      this.updateAriaAttributes();
    }
  }

  updateAriaAttributes() {
    if (this.ariaLabelledby) {
      this.dialog.setAttribute("aria-labelledby", this.ariaLabelledby);
    } else {
      this.dialog.removeAttribute("aria-labelledby");
    }

    if (this.ariaDescribedby) {
      this.dialog.setAttribute("aria-describedby", this.ariaDescribedby);
    } else {
      this.dialog.removeAttribute("aria-describedby");
    }

    if (this.ariaModal) {
      this.dialog.setAttribute("aria-modal", this.ariaModal);
    } else {
      this.dialog.removeAttribute("aria-modal");
    }
  }

  showDialog() {
    const showEvent = new CustomEvent("my-show", {
      bubbles: true,
      composed: true,
    });

    this.dispatchEvent(showEvent);

    if (showEvent.defaultPrevented) {
      this.open = false;
      return;
    }

    this.addOpenListeners();
    this.originalTrigger = document.activeElement;

    this.dialog.showModal();
    this.updateAriaAttributes();

    // Focus the first focusable element
    requestAnimationFrame(() => {
      const elementToFocus = this.querySelector("[autofocus]");
      if (elementToFocus && typeof elementToFocus.focus === "function") {
        elementToFocus.focus();
      }
    });

    // Emit after-show event
    setTimeout(() => {
      this.dispatchEvent(
        new CustomEvent("my-after-show", {
          bubbles: true,
          composed: true,
        }),
      );
    }, 100);
  }

  hideDialog(source = this) {
    const hideEvent = new CustomEvent("my-hide", {
      bubbles: true,
      composed: true,
      detail: { source },
    });

    this.dispatchEvent(hideEvent);

    if (hideEvent.defaultPrevented) {
      this.open = true;
      if (source !== this && !this.lightDismiss) {
        this.pulseDialog();
      }
      return;
    }

    this.removeOpenListeners();

    this.dialog.close();
    this.open = false;

    // Return focus to the original trigger
    const trigger = this.originalTrigger;
    if (typeof trigger?.focus === "function") {
      setTimeout(() => trigger.focus());
    }

    // Emit after-hide event
    setTimeout(() => {
      this.dispatchEvent(
        new CustomEvent("my-after-hide", {
          bubbles: true,
          composed: true,
        }),
      );
    }, 100);
  }

  addOpenListeners() {
    document.addEventListener("keydown", this.handleDocumentKeyDown);
    this.addEventListener("click", this.handleDialogClick);
  }

  removeOpenListeners() {
    document.removeEventListener("keydown", this.handleDocumentKeyDown);
    this.removeEventListener("click", this.handleDialogClick);
  }

  handleDocumentKeyDown(event) {
    if (event.key === "Escape" && this.open) {
      event.preventDefault();
      event.stopPropagation();
      this.hideDialog(this.dialog);
    }
  }

  handleDialogCancel(event) {
    event.preventDefault();
    this.hideDialog(this.dialog);
  }

  handleDialogPointerDown(event) {
    if (event.target === this.dialog) {
      if (this.lightDismiss) {
        this.hideDialog(this.dialog);
      } else {
        this.pulseDialog();
      }
    }
  }

  handleDialogClick(event) {
    const target = event.target;
    const closeButton = target.closest('[data-dialog="close"]');
    if (closeButton) {
      event.stopPropagation();
      this.hideDialog(closeButton);
    }
  }

  pulseDialog() {
    this.dialog.classList.remove("pulse");
    void this.dialog.offsetWidth; // Force reflow
    this.dialog.classList.add("pulse");
  }

  // Public methods
  show() {
    this.open = true;
  }

  hide() {
    this.open = false;
  }

  render() {
    return html`
      <dialog
        @cancel=${this.handleDialogCancel}
        @pointerdown=${this.handleDialogPointerDown}
      >
        <slot></slot>
      </dialog>
    `;
  }
}

customElements.define("my-dialog", MyDialog);
