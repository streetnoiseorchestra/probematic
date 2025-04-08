import {
    computePosition,
    flip,
} from "https://cdn.jsdelivr.net/npm/@floating-ui/dom@1.6.13/+esm";

export function ActionMenuPopover(container) {
    const trigger = container.querySelector("[popovertarget]");
    const popover = container.querySelector("[popover]");
    const update = () => {
        computePosition(trigger, popover, {
            placement: "bottom",
            middleware: [flip()],
        }).then(({ x, y }) => {
            Object.assign(popover.style, {
                left: `${x}px`,
                top: `${y}px`,
            });
        });
    };
    popover.addEventListener("toggle", update);
}
