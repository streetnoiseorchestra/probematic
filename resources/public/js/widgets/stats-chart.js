(function () {
  const activeCharts = {};
  let renderHandle = null;

  function chartData(canvas) {
    const dataSelector = canvas.getAttribute('data-values');
    const dataElement = dataSelector && document.querySelector(dataSelector);

    if (!dataElement) return null;

    return JSON.parse(dataElement.textContent);
  }

  function HistogramChart(canvas) {
    if (!window.Chart) return;

    const id = canvas.getAttribute('id');
    const data = chartData(canvas);

    if (!id || !data) return;

    const { values, title, color, yAxisLabel, xAxisLabel } = data;
    const fontFamily = getComputedStyle(document.body).fontFamily;

    if (activeCharts[id]) {
      activeCharts[id].destroy();
      activeCharts[id] = null;
    }

    Chart.defaults.font.size = 16;
    Chart.defaults.font.family = fontFamily;

    activeCharts[id] = new Chart(canvas, {
      type: 'bar',
      data: {
        labels: values.map(d => d.x.toString()),
        datasets: [
          {
            label: title,
            data: values.map(d => d.y),
            backgroundColor: color,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
          x: {
            title: {
              display: true,
              text: xAxisLabel,
            },
            grid: {
              display: false,
            },
            ticks: {
              padding: 0,
              font: {
                family: fontFamily,
                size: 14,
              },
            },
            beginAtZero: true,
          },
          y: {
            title: {
              display: true,
              padding: 0,
              text: yAxisLabel,
            },
            ticks: {
              padding: 0,
              font: {
                family: fontFamily,
                size: 14,
              },
            },
            beginAtZero: true,
          },
        },
        plugins: {
          tooltip: false,
          legend: {
            labels: {
              font: {
                family: fontFamily,
                size: 16,
              },
            },
          },
        },
      },
    });
  }

  function renderAll(root) {
    const target = root || document;
    const canvases = target.matches && target.matches('canvas.histogram-chart')
      ? [target]
      : Array.from(target.querySelectorAll ? target.querySelectorAll('canvas.histogram-chart') : []);

    canvases.forEach(HistogramChart);
  }

  function scheduleRender(root) {
    if (renderHandle) cancelAnimationFrame(renderHandle);
    renderHandle = requestAnimationFrame(() => {
      renderHandle = null;
      renderAll(root || document);
    });
  }

  function observeStatsCharts() {
    if (!document.body) return;

    const observer = new MutationObserver(mutations => {
      if (mutations.some(mutation => Array.from(mutation.addedNodes).some(node => {
        if (!(node instanceof Element)) return false;
        return node.matches('canvas.histogram-chart') || node.querySelector('canvas.histogram-chart');
      }))) {
        scheduleRender(document);
      }
    });

    observer.observe(document.body, { childList: true, subtree: true });
  }

  window.SnoStatsCharts = { renderAll };

  if (document.readyState !== 'loading') {
    renderAll(document);
    observeStatsCharts();
  } else {
    document.addEventListener('DOMContentLoaded', function () {
      renderAll(document);
      observeStatsCharts();
    });
  }
})();
