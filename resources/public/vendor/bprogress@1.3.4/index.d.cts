type BProgressDirection = 'ltr' | 'rtl';
type BProgressPositionUsing = 'translate3d' | 'translate' | 'margin' | 'width' | '';
interface BProgressOptions {
    minimum?: number;
    maximum?: number;
    template?: string | null;
    easing?: string;
    speed?: number;
    trickle?: boolean;
    trickleSpeed?: number;
    showSpinner?: boolean;
    parent?: HTMLElement | string;
    positionUsing?: BProgressPositionUsing;
    barSelector?: string;
    indeterminateSelector?: string;
    spinnerSelector?: string;
    direction?: BProgressDirection;
    indeterminate?: boolean;
}
type SpinnerPosition = 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right';

declare class BProgress {
    static settings: Required<BProgressOptions>;
    static status: number | null;
    private static pending;
    private static isPaused;
    static reset(): typeof BProgress;
    static configure(options: Partial<BProgressOptions>): typeof BProgress;
    static isStarted(): boolean;
    /**
     * Set the progress status.
     * This method updates the progress status for every progress element present in the DOM.
     * If a template is provided, it will create a new progress element if one does not already exist.
     * If the template is null, it relies on user-inserted elements.
     */
    static set(n: number): typeof BProgress;
    static start(): typeof BProgress;
    static done(force?: boolean): typeof BProgress;
    static inc(amount?: number): typeof BProgress;
    static dec(amount?: number): typeof BProgress;
    static trickle(): typeof BProgress;
    static promise($promise: any): typeof BProgress;
    /**
     * Renders the BProgress component.
     * If a template is provided, it will create a progress element if none exists in the parent.
     * If the template is null, it relies on the user to insert their own elements marked with the "bprogress" class.
     * When using indeterminate mode with a custom template, the template should include the indeterminate element.
     */
    static render(fromStart?: boolean): HTMLElement[];
    /**
     * Remove the progress element from the DOM.
     * If a progress element is provided, only that element is removed;
     * otherwise, all progress elements and associated classes are removed.
     * For user-provided templates (when settings.template === null), the element
     * is hidden instead of being removed.
     */
    static remove(progressElement?: HTMLElement): void;
    static pause(): typeof BProgress;
    static resume(): typeof BProgress;
    static isRendered(): boolean;
    static getPositioningCSS(): "translate3d" | "translate" | "margin";
    private static queue;
    private static next;
    private static initPositionUsing;
    private static barPositionCSS;
}

type CssOptions = {
    color?: string;
    height?: string;
    spinnerPosition?: SpinnerPosition;
};
declare const css: ({ color, height, spinnerPosition, }: CssOptions) => string;

declare function isSameURL(target: URL, current: URL): boolean;
declare function isSameURLWithoutSearch(target: URL, current: URL): boolean;

declare function getAnchorProperty<T extends HTMLAnchorElement | SVGAElement, K extends keyof T, P extends T[K]>(a: T, key: K): P extends SVGAnimatedString ? string : P;

export { BProgress, type BProgressDirection, type BProgressOptions, type BProgressPositionUsing, type CssOptions, type SpinnerPosition, css, getAnchorProperty, isSameURL, isSameURLWithoutSearch };
