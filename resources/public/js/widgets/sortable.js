const initSortable = (target) => {
    var sortables = target.querySelectorAll(".sortable");
    for (var i = 0; i < sortables.length; i++) {
        var sortable = sortables[i];
        console.log(sortable);
        new Sortable(sortable, {
            animation: 150,
            ghostClass: "bg-blue-300",
            // no handle makes it easier to drag
            handle: ".drag-handle",
            onUpdate: (evt) => {
                sortable
                    .querySelectorAll("input[data-sort-order]")
                    .forEach((el, i) => {
                        //console.log("setting", el, i);
                        el.setAttribute("value", i);
                    });
            },
        });
    }
};

const initEventSortable = (targetId) => {
    const sortContainer = document.getElementById(targetId);
    new Sortable(sortContainer, {
        animation: 150,
        ghostClass: "bg-sno-green-300",
        onEnd: (evt) => {
            const data = {};
            sortContainer
                .querySelectorAll("input[data-sort-order]")
                .forEach((el, i) => {
                    const id = el.getAttribute("data-sort-order");
                    if (!id) {
                        console.warn(
                            "no data-sort-order value found on dragged item",
                        );
                        return;
                    }
                    data[id] = i;
                });
            sortContainer.dispatchEvent(
                new CustomEvent("reordered", {
                    detail: {
                        orderInfo: data,
                        // oldIndex: evt.oldIndex,
                        // newIndex: evt.newIndex,
                    },
                }),
            );
        },
    });
};

function maybeInitSortable(evt) {
    if (evt) {
        if (evt.target.querySelector(".sortable")) {
            initSortable(evt.target);
        }
    } else {
        document.querySelectorAll(".sortable-container").forEach(initSortable);
    }
}

document.addEventListener("DOMContentLoaded", function () {
    maybeInitSortable();
});

document.body.addEventListener("htmx:afterSettle", function (evt) {
    maybeInitSortable(evt);
});
