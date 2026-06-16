"use strict";
var BProgressJS = (() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

  // src/index.ts
  var index_exports = {};
  __export(index_exports, {
    BProgress: () => BProgress,
    css: () => css,
    getAnchorProperty: () => getAnchorProperty,
    isSameURL: () => isSameURL,
    isSameURLWithoutSearch: () => isSameURLWithoutSearch
  });

  // src/utils/clamp.ts
  function clamp(n, min, max) {
    return Math.max(min, Math.min(n, max));
  }

  // src/utils/to-bar-perc.ts
  function toBarPerc(n, direction) {
    if (direction === "rtl") return (1 - n) * 100;
    return (-1 + n) * 100;
  }

  // src/utils/to-css.ts
  function toCss(element, properties, value) {
    if (typeof properties === "string") {
      if (value !== void 0) {
        element.style[properties] = value;
      }
    } else {
      for (const prop in properties) {
        if (properties.hasOwnProperty(prop)) {
          const val = properties[prop];
          if (val !== void 0) {
            element.style[prop] = val;
          }
        }
      }
    }
  }

  // src/utils/class.ts
  function addClass(element, name) {
    element.classList.add(name);
  }
  function removeClass(element, name) {
    element.classList.remove(name);
  }

  // src/utils/element.ts
  function removeElement(element) {
    if (element && element.parentNode) {
      element.parentNode.removeChild(element);
    }
  }

  // src/progress.ts
  var defaultSettings = {
    minimum: 0.08,
    maximum: 1,
    // If template is null, the user can insert their own template in the DOM.
    template: `<div class="bar"><div class="peg"></div></div>
             <div class="spinner"><div class="spinner-icon"></div></div>
             <div class="indeterminate"><div class="inc"></div><div class="dec"></div></div>`,
    easing: "linear",
    positionUsing: "",
    speed: 200,
    trickle: true,
    trickleSpeed: 200,
    showSpinner: true,
    indeterminate: false,
    indeterminateSelector: ".indeterminate",
    barSelector: ".bar",
    spinnerSelector: ".spinner",
    parent: "body",
    direction: "ltr"
  };
  var BProgress = class {
    static settings = defaultSettings;
    static status = null;
    // Queue for animation functions
    static pending = [];
    static isPaused = false;
    // Reset the progress
    static reset() {
      this.status = null;
      this.isPaused = false;
      this.pending = [];
      this.settings = defaultSettings;
      return this;
    }
    // Configure BProgress with new options
    static configure(options) {
      Object.assign(this.settings, options);
      return this;
    }
    // Check if BProgress has started
    static isStarted() {
      return typeof this.status === "number";
    }
    /**
     * Set the progress status.
     * This method updates the progress status for every progress element present in the DOM.
     * If a template is provided, it will create a new progress element if one does not already exist.
     * If the template is null, it relies on user-inserted elements.
     */
    static set(n) {
      if (this.isPaused) return this;
      const started = this.isStarted();
      n = clamp(n, this.settings.minimum, this.settings.maximum);
      this.status = n === this.settings.maximum ? null : n;
      const progressElements = this.render(!started);
      const speed = this.settings.speed;
      const ease = this.settings.easing;
      progressElements.forEach((progress) => progress.offsetWidth);
      this.queue((next) => {
        progressElements.forEach((progress) => {
          if (!this.settings.indeterminate) {
            const bar = progress.querySelector(
              this.settings.barSelector
            );
            toCss(bar, this.barPositionCSS({ n, speed, ease }));
          }
        });
        if (n === this.settings.maximum) {
          progressElements.forEach((progress) => {
            toCss(progress, { transition: "none", opacity: "1" });
            progress.offsetWidth;
          });
          setTimeout(() => {
            progressElements.forEach((progress) => {
              toCss(progress, {
                transition: `all ${speed}ms ${ease}`,
                opacity: "0"
              });
            });
            setTimeout(() => {
              progressElements.forEach((progress) => {
                this.remove(progress);
                if (this.settings.template === null) {
                  toCss(progress, { transition: "none", opacity: "1" });
                }
              });
              next();
            }, speed);
          }, speed);
        } else {
          setTimeout(next, speed);
        }
      });
      return this;
    }
    // Start the progress bar
    static start() {
      if (!this.status) this.set(0);
      const work = () => {
        if (this.isPaused) return;
        setTimeout(() => {
          if (!this.status) return;
          this.trickle();
          work();
        }, this.settings.trickleSpeed);
      };
      if (this.settings.trickle) work();
      return this;
    }
    // Complete the progress
    static done(force) {
      if (!force && !this.status) return this;
      return this.inc(0.3 + 0.5 * Math.random()).set(1);
    }
    // Increment the progress
    static inc(amount) {
      if (this.isPaused || this.settings.indeterminate) return this;
      let n = this.status;
      if (!n) {
        return this.start();
      } else if (n > 1) {
        return this;
      } else {
        if (typeof amount !== "number") {
          if (n >= 0 && n < 0.2) {
            amount = 0.1;
          } else if (n >= 0.2 && n < 0.5) {
            amount = 0.04;
          } else if (n >= 0.5 && n < 0.8) {
            amount = 0.02;
          } else if (n >= 0.8 && n < 0.99) {
            amount = 5e-3;
          } else {
            amount = 0;
          }
        }
        n = clamp(n + amount, 0, 0.994);
        return this.set(n);
      }
    }
    // Decrement the progress
    static dec(amount) {
      if (this.isPaused || this.settings.indeterminate) return this;
      let n = this.status;
      if (typeof n !== "number") return this;
      if (typeof amount !== "number") {
        if (n > 0.8) {
          amount = 0.1;
        } else if (n > 0.5) {
          amount = 0.05;
        } else if (n > 0.2) {
          amount = 0.02;
        } else {
          amount = 0.01;
        }
      }
      n = clamp(n - amount, 0, 0.994);
      return this.set(n);
    }
    // Advance the progress (trickle)
    static trickle() {
      if (this.isPaused || this.settings.indeterminate) return this;
      return this.inc();
    }
    // Handle jQuery promises (for compatibility)
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    static promise($promise) {
      if (!$promise || $promise.state() === "resolved") {
        return this;
      }
      let initial = 0, current = 0;
      if (current === 0) {
        this.start();
      }
      initial++;
      current++;
      $promise.always(() => {
        current--;
        if (current === 0) {
          initial = 0;
          this.done();
        } else {
          this.set((initial - current) / initial);
        }
      });
      return this;
    }
    /**
     * Renders the BProgress component.
     * If a template is provided, it will create a progress element if none exists in the parent.
     * If the template is null, it relies on the user to insert their own elements marked with the "bprogress" class.
     * When using indeterminate mode with a custom template, the template should include the indeterminate element.
     */
    static render(fromStart = false) {
      const parent = typeof this.settings.parent === "string" ? document.querySelector(this.settings.parent) : this.settings.parent;
      const progressElements = parent ? Array.from(parent.querySelectorAll(".bprogress")) : [];
      if (this.settings.template !== null && progressElements.length === 0) {
        addClass(document.documentElement, "bprogress-busy");
        const progress = document.createElement("div");
        addClass(progress, "bprogress");
        progress.innerHTML = this.settings.template;
        if (parent !== document.body) {
          addClass(parent, "bprogress-custom-parent");
        }
        parent.appendChild(progress);
        progressElements.push(progress);
      }
      progressElements.forEach((progress) => {
        if (this.settings.template === null) {
          progress.style.display = "";
        }
        addClass(document.documentElement, "bprogress-busy");
        if (parent !== document.body) {
          addClass(parent, "bprogress-custom-parent");
        }
        if (!this.settings.indeterminate) {
          const bar = progress.querySelector(
            this.settings.barSelector
          );
          const perc = fromStart ? toBarPerc(0, this.settings.direction) : toBarPerc(this.status || 0, this.settings.direction);
          toCss(
            bar,
            this.barPositionCSS({
              n: this.status || 0,
              speed: this.settings.speed,
              ease: this.settings.easing,
              perc
            })
          );
          const indeterminateElem = progress.querySelector(
            this.settings.indeterminateSelector
          );
          if (indeterminateElem) {
            indeterminateElem.style.display = "none";
          }
        } else {
          const bar = progress.querySelector(
            this.settings.barSelector
          );
          if (bar) {
            bar.style.display = "none";
          }
          const indeterminateElem = progress.querySelector(
            this.settings.indeterminateSelector
          );
          if (indeterminateElem) {
            indeterminateElem.style.display = "";
          }
        }
        if (this.settings.template === null) {
          const spinner = progress.querySelector(
            this.settings.spinnerSelector
          );
          if (spinner) {
            spinner.style.display = this.settings.showSpinner ? "block" : "none";
          }
        } else {
          if (!this.settings.showSpinner) {
            const spinner = progress.querySelector(
              this.settings.spinnerSelector
            );
            if (spinner) removeElement(spinner);
          }
        }
      });
      return progressElements;
    }
    /**
     * Remove the progress element from the DOM.
     * If a progress element is provided, only that element is removed;
     * otherwise, all progress elements and associated classes are removed.
     * For user-provided templates (when settings.template === null), the element
     * is hidden instead of being removed.
     */
    static remove(progressElement) {
      if (progressElement) {
        if (this.settings.template === null) {
          progressElement.style.display = "none";
        } else {
          removeElement(progressElement);
        }
      } else {
        removeClass(document.documentElement, "bprogress-busy");
        const parent = typeof this.settings.parent === "string" ? document.querySelectorAll(this.settings.parent) : [this.settings.parent];
        parent.forEach((p) => {
          removeClass(p, "bprogress-custom-parent");
        });
        const progresses = document.querySelectorAll(".bprogress");
        progresses.forEach((progress) => {
          const elem = progress;
          if (this.settings.template === null) {
            elem.style.display = "none";
          } else {
            removeElement(elem);
          }
        });
      }
    }
    // Pause the progress
    static pause() {
      if (!this.isStarted() || this.settings.indeterminate) return this;
      this.isPaused = true;
      return this;
    }
    // Resume the progress
    static resume() {
      if (!this.isStarted() || this.settings.indeterminate) return this;
      this.isPaused = false;
      if (this.settings.trickle) {
        const work = () => {
          if (this.isPaused) return;
          setTimeout(() => {
            if (!this.status) return;
            this.trickle();
            work();
          }, this.settings.trickleSpeed);
        };
        work();
      }
      return this;
    }
    // Check if BProgress is rendered in the DOM
    static isRendered() {
      return document.querySelectorAll(".bprogress").length > 0;
    }
    // Determine the CSS positioning method to use
    static getPositioningCSS() {
      const bodyStyle = document.body.style;
      const vendorPrefix = "WebkitTransform" in bodyStyle ? "Webkit" : "MozTransform" in bodyStyle ? "Moz" : "msTransform" in bodyStyle ? "ms" : "OTransform" in bodyStyle ? "O" : "";
      if (`${vendorPrefix}Perspective` in bodyStyle) {
        return "translate3d";
      } else if (`${vendorPrefix}Transform` in bodyStyle) {
        return "translate";
      } else {
        return "margin";
      }
    }
    // Queue function for animations
    static queue(fn) {
      this.pending.push(fn);
      if (this.pending.length === 1) this.next();
    }
    static next() {
      const fn = this.pending.shift();
      if (fn) fn(this.next.bind(this));
    }
    static initPositionUsing() {
      if (this.settings.positionUsing === "") {
        this.settings.positionUsing = this.getPositioningCSS();
      }
    }
    // Compute the CSS for positioning the bar
    static barPositionCSS({
      n,
      speed,
      ease,
      perc
    }) {
      this.initPositionUsing();
      let barCSS = {};
      const computedPerc = perc ?? toBarPerc(n, this.settings.direction);
      if (this.settings.positionUsing === "translate3d") {
        barCSS = {
          transform: `translate3d(${computedPerc}%,0,0)`
        };
      } else if (this.settings.positionUsing === "translate") {
        barCSS = {
          transform: `translate(${computedPerc}%,0)`
        };
      } else if (this.settings.positionUsing === "width") {
        barCSS = {
          width: `${this.settings.direction === "rtl" ? 100 - computedPerc : computedPerc + 100}%`,
          ...this.settings.direction === "rtl" ? { right: "0", left: "auto" } : {}
        };
      } else if (this.settings.positionUsing === "margin") {
        barCSS = this.settings.direction === "rtl" ? { "margin-left": `${-computedPerc}%` } : { "margin-right": `${-computedPerc}%` };
      }
      barCSS.transition = `all ${speed}ms ${ease}`;
      return barCSS;
    }
  };

  // src/lib/css.ts
  var css = ({
    color = "#29d",
    height = "2px",
    spinnerPosition = "top-right"
  }) => `
:root {
  --bprogress-color: ${color};
  --bprogress-height: ${height};
  --bprogress-spinner-size: 18px;
  --bprogress-spinner-animation-duration: 400ms;
  --bprogress-spinner-border-size: 2px;
  --bprogress-box-shadow: 0 0 10px ${color}, 0 0 5px ${color};
  --bprogress-z-index: 99999;
  --bprogress-spinner-top: ${spinnerPosition === "top-right" || spinnerPosition === "top-left" ? "15px" : "auto"};
  --bprogress-spinner-bottom: ${spinnerPosition === "bottom-right" || spinnerPosition === "bottom-left" ? "15px" : "auto"};
  --bprogress-spinner-right: ${spinnerPosition === "top-right" || spinnerPosition === "bottom-right" ? "15px" : "auto"};
  --bprogress-spinner-left: ${spinnerPosition === "top-left" || spinnerPosition === "bottom-left" ? "15px" : "auto"};
}

.bprogress {
  width: 0;
  height: 0;
  pointer-events: none;
  z-index: var(--bprogress-z-index);
}

.bprogress .bar {
  background: var(--bprogress-color);
  position: fixed;
  z-index: var(--bprogress-z-index);
  top: 0;
  left: 0;
  width: 100%;
  height: var(--bprogress-height);
}

/* Fancy blur effect */
.bprogress .peg {
  display: block;
  position: absolute;
  right: 0;
  width: 100px;
  height: 100%;
  box-shadow: var(--bprogress-box-shadow);
  opacity: 1.0;
  transform: rotate(3deg) translate(0px, -4px);
}

/* Remove these to get rid of the spinner */
.bprogress .spinner {
  display: block;
  position: fixed;
  z-index: var(--bprogress-z-index);
  top: var(--bprogress-spinner-top);
  bottom: var(--bprogress-spinner-bottom);
  right: var(--bprogress-spinner-right);
  left: var(--bprogress-spinner-left);
}

.bprogress .spinner-icon {
  width: var(--bprogress-spinner-size);
  height: var(--bprogress-spinner-size);
  box-sizing: border-box;
  border: solid var(--bprogress-spinner-border-size) transparent;
  border-top-color: var(--bprogress-color);
  border-left-color: var(--bprogress-color);
  border-radius: 50%;
  -webkit-animation: bprogress-spinner var(--bprogress-spinner-animation-duration) linear infinite;
  animation: bprogress-spinner var(--bprogress-spinner-animation-duration) linear infinite;
}

.bprogress-custom-parent {
  overflow: hidden;
  position: relative;
}

.bprogress-custom-parent .bprogress .spinner,
.bprogress-custom-parent .bprogress .bar {
  position: absolute;
}

.bprogress .indeterminate {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: var(--bprogress-height);
  overflow: hidden;
}

.bprogress .indeterminate .inc,
.bprogress .indeterminate .dec {
  position: absolute;
  top: 0;
  height: 100%;
  background-color: var(--bprogress-color);
}

.bprogress .indeterminate .inc {
  animation: bprogress-indeterminate-increase 2s infinite;
}

.bprogress .indeterminate .dec {
  animation: bprogress-indeterminate-decrease 2s 0.5s infinite;
}

@-webkit-keyframes bprogress-spinner {
  0%   { -webkit-transform: rotate(0deg); transform: rotate(0deg); }
  100% { -webkit-transform: rotate(360deg); transform: rotate(360deg); }
}

@keyframes bprogress-spinner {
  0%   { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

@keyframes bprogress-indeterminate-increase {
  from { left: -5%; width: 5%; }
  to { left: 130%; width: 100%; }
}

@keyframes bprogress-indeterminate-decrease {
  from { left: -80%; width: 80%; }
  to { left: 110%; width: 10%; }
}
`;

  // src/lib/same-url.ts
  function isSameURL(target, current) {
    const cleanTarget = target.protocol + "//" + target.host + target.pathname + target.search;
    const cleanCurrent = current.protocol + "//" + current.host + current.pathname + current.search;
    return cleanTarget === cleanCurrent;
  }
  function isSameURLWithoutSearch(target, current) {
    const cleanTarget = target.protocol + "//" + target.host + target.pathname;
    const cleanCurrent = current.protocol + "//" + current.host + current.pathname;
    return cleanTarget === cleanCurrent;
  }

  // src/lib/get-anchor-property.ts
  function parsePath(path) {
    const hashIndex = path.indexOf("#");
    const queryIndex = path.indexOf("?");
    const hasQuery = queryIndex > -1 && (hashIndex < 0 || queryIndex < hashIndex);
    if (hasQuery || hashIndex > -1) {
      return {
        pathname: path.substring(0, hasQuery ? queryIndex : hashIndex),
        query: hasQuery ? path.substring(queryIndex, hashIndex > -1 ? hashIndex : void 0) : "",
        hash: hashIndex > -1 ? path.slice(hashIndex) : ""
      };
    }
    return { pathname: path, query: "", hash: "" };
  }
  function addPathPrefix(path, prefix) {
    if (!path.startsWith("/") || !prefix) {
      return path;
    }
    const { pathname, query, hash } = parsePath(path);
    return `${prefix}${pathname}${query}${hash}`;
  }
  function getAnchorProperty(a, key) {
    if (typeof key === "string" && key === "data-disable-progress") {
      const dataKey = key.substring(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
      return a.dataset[dataKey];
    }
    const prop = a[key];
    if (prop instanceof SVGAnimatedString) {
      const value = prop.baseVal;
      if (key === "href") {
        return addPathPrefix(value, location.origin);
      }
      return value;
    }
    return prop;
  }
  return __toCommonJS(index_exports);
})();
