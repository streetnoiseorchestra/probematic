const activePollCharts = new WeakMap();

function chartData(canvas) {
  const selector = canvas.getAttribute('data-poll-values');
  const source = selector && document.querySelector(selector);

  if (!source) {
    return null;
  }

  try {
    return JSON.parse(source.textContent || '{}');
  } catch (_err) {
    return null;
  }
}

function destroyChart(canvas) {
  const existing = activePollCharts.get(canvas);

  if (existing) {
    existing.destroy();
    activePollCharts.delete(canvas);
  }
}

function renderChart(canvas) {
  if (!window.Chart) {
    return null;
  }

  const data = chartData(canvas);

  if (!data) {
    return null;
  }

  destroyChart(canvas);

  const labels = Array.isArray(data.labels) ? data.labels : [];
  const values = Array.isArray(data.values) ? data.values : [];
  const totalVoters = Number(data.totalVoters || 0);
  const fontFamily = getComputedStyle(document.body).fontFamily;
  const plugins = window.ChartDataLabels ? [window.ChartDataLabels] : [];

  const chart = new window.Chart(canvas, {
    type: 'bar',
    data: {
      labels,
      datasets: [
        {
          data: values,
          hoverBorderColor: '#fff',
          backgroundColor: '#22c55e',
          borderColor: 'transparent',
          borderWidth: 0,
        },
      ],
    },
    options: {
      maintainAspectRatio: false,
      indexAxis: 'y',
      barThickness: 20,
      responsive: true,
      aspectRatio: 1.1,
      animation: { duration: 0 },
      scales: {
        x: {
          beginAtZero: true,
          ticks: { display: false },
          grid: { display: false },
        },
        y: {
          ticks: {
            callback(value) {
              return `   ${this.getLabelForValue(value)}`;
            },
            font: {
              family: fontFamily,
              size: 16,
            },
          },
          grid: { display: false },
          beginAtZero: true,
        },
      },
      plugins: {
        tooltip: false,
        legend: { display: false },
        datalabels: {
          color: '#333',
          backgroundColor: 'rgba(255, 255, 255, 0.5)',
          borderRadius: 2,
          font: {
            family: fontFamily,
            size: 16,
          },
          padding: {
            top: 2,
            right: 6,
            bottom: 2,
            left: 6,
          },
          formatter(votes) {
            const percent = totalVoters > 0 ? (Number(votes) * 100.0) / totalVoters : 0;
            const formattedPercent = Number(percent.toFixed(1)).toLocaleString('de-AT', {
              maximumFractionDigits: 1,
            });

            return `${votes} (${formattedPercent}%)`;
          },
        },
      },
    },
    plugins,
  });

  activePollCharts.set(canvas, chart);
  return chart;
}

function initAll(root = document) {
  root.querySelectorAll('canvas.poll-chart').forEach(renderChart);
}

function observeCharts() {
  if (!document.body) {
    return;
  }

  const observer = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (!(node instanceof Element)) {
          continue;
        }

        if (node.matches('canvas.poll-chart')) {
          renderChart(node);
        } else {
          initAll(node);
        }
      }
    }
  });

  observer.observe(document.body, { childList: true, subtree: true });
}

function start() {
  initAll();
  observeCharts();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', start, { once: true });
} else {
  start();
}

window.SnoPollCharts = {
  init: initAll,
  render: renderChart,
  destroy: destroyChart,
};
