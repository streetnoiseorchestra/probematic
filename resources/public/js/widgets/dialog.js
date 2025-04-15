/*
 * MyDialog - A simple headless dialog web component wrapping HTML's <dialog>
 *
 * Why not use <dialog> directly? Because <dialog>'s open property is horrible.
 * See: https://developer.mozilla.org/en-US/docs/Web/API/HTMLDialogElement/open
 *
 * We want property-driven web components!
 *
 * Features:
 *
 * 1. HTML Properties:
 *    - open: Boolean - Controls dialog visibility. Default: false
 *    - light-dismiss: Boolean - Enables closing when clicking outside. Default: false
 *    - body-id: String - ID of the dialog body element for improved light-dismiss. Default: null
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
 *    - Focus management - Returns focus to trigger element when closed
 *    - Escape key support - Closes dialog when Escape is pressed
 *    - Accessibility - Full support for ARIA attributes and keyboard interaction
 *    - Fully customizable styling (truly headless)
 *
 * 5. Styling:
 *    - CSS Parts:
 *      - ::part(dialog): Allows styling the dialog element including the ::backdrop
 *        Example:
 *
 *            my-dialog::part(dialog)::backdrop {
 *              background-color: rgba(0, 0, 0, 0.8);
 *              backdrop-filter: blur(5px);
 *            }
 *
 *            my-dialog::part(dialog) {
 *              max-width: 500px;
 *              border-radius: 8px;
 *              box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
 *            }
 *
 * Usage Example:
 *
 * <my-dialog aria-labelledby="dialog-title" aria-describedby="dialog-description" open="true">
 *   <div id="dialog-body">
 *     <h2 id="dialog-title">Dialog Title</h2>
 *     <div>
 *       <p>Dialog content goes here</p>
 *     </div>
 *     <button data-dialog="close">Close</button>
 *   </div>
 * </my-dialog>
 *
 * <!-- With light-dismiss and body-id -->
 * <my-dialog light-dismiss="true" body-id="dialog-body" aria-labelledby="dialog-title">
 *   <div id="dialog-body" class="fixed inset-0 z-10 w-screen overflow-y-auto">
 *     <div class="dialog-content">
 *       <h2 id="dialog-title">Dialog Title</h2>
 *       <p>Click outside this content to close</p>
 *     </div>
 *   </div>
 * </my-dialog>
 */

import { LitElement, html, css } from "lit-core.js";

export class MyDialog extends LitElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    lightDismiss: { type: Boolean, attribute: "light-dismiss" },
    bodyId: { type: String, attribute: "body-id" },
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
      display: none;
    }

    :host([open]) {
      display: block;
    }

    dialog {
      border: none;
      padding: 0;
    }
  `;

  constructor() {
    super();
    this.open = false;
    this.lightDismiss = false;
    this.bodyId = null;
    this.ariaModal = "true"; // Default to true for modal dialogs
    this.handleDocumentKeyDown = this.handleDocumentKeyDown.bind(this);
    this.handleDialogClick = this.handleDialogClick.bind(this);
    this.handleDialogPointerDown = this.handleDialogPointerDown.bind(this);
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
  }

  removeOpenListeners() {
    document.removeEventListener("keydown", this.handleDocumentKeyDown);
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
    if (!this.lightDismiss) return;
    
    // If a body-id is specified, check if the click is outside that element
    if (this.bodyId) {
      const bodyElement = this.querySelector(`#${this.bodyId}`);
      if (bodyElement) {
        // Check if the event target is not inside the specified body element
        if (!bodyElement.contains(event.target) && event.target !== bodyElement) {
          this.hideDialog(this.dialog);
        }
        return;
      }
    }
    
    // Default behavior: check if click was directly on the dialog backdrop
    if (event.target === this.dialog) {
      this.hideDialog(this.dialog);
    }
  }

  handleDialogClick(event) {
    const target = event.target;
    const button = target.closest('[data-dialog="close"]');
    if (button) {
      event.stopPropagation();
      this.hideDialog(button);
    }
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
        part="dialog"
        @cancel=${this.handleDialogCancel}
        @click=${this.handleDialogClick}
        @pointerdown=${this.handleDialogPointerDown}
      >
        <slot></slot>
      </dialog>
    `;
  }
}

customElements.define("my-dialog", MyDialog);
