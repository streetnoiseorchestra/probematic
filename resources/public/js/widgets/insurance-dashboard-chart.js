const activeInsuranceCharts = new WeakMap();

function chartData(canvas) {
  const selector = canvas.getAttribute('data-values');
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

function cssVar(styles, name, fallback) {
  return styles.getPropertyValue(name).trim() || fallback;
}

function chartColors() {
  const styles = getComputedStyle(document.documentElement);
  const bandColor = cssVar(styles, '--wa-color-success-fill-loud', '#22c55e');
  const privateColor = cssVar(styles, '--wa-color-warning-fill-loud', '#f97316');
  const emptyColor = cssVar(styles, '--wa-color-neutral-fill-normal', '#9ca3af');

  return { bandColor, privateColor, emptyColor };
}

function destroyChart(canvas) {
  const existing = activeInsuranceCharts.get(canvas);

  if (existing) {
    existing.destroy();
    activeInsuranceCharts.delete(canvas);
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
  const rawValues = Array.isArray(data.values) ? data.values.map(value => Number(value || 0)) : [];
  const total = rawValues.reduce((sum, value) => sum + value, 0);
  const { bandColor, privateColor, emptyColor } = chartColors();
  const fontFamily = getComputedStyle(document.body).fontFamily;
  const plugins = window.ChartDataLabels ? [window.ChartDataLabels] : [];
  const values = total > 0 ? rawValues : [1];
  const chartLabels = total > 0 ? labels : [data.emptyLabel || 'No data'];
  const colors = total > 0 ? [bandColor, privateColor] : [emptyColor];

  const chart = new window.Chart(canvas, {
    type: 'pie',
    data: {
      labels: chartLabels,
      datasets: [
        {
          data: values,
          backgroundColor: colors,
          borderColor: 'transparent',
          borderWidth: 0,
          hoverBorderColor: '#fff',
          hoverBorderWidth: 2,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: { duration: 0 },
      plugins: {
        legend: { display: false },
        tooltip: {
          enabled: total > 0,
        },
        datalabels: {
          display(context) {
            return total > 0 && Number(context.dataset.data[context.dataIndex] || 0) > 0;
          },
          color: '#fff',
          font: {
            family: fontFamily,
            size: 14,
            weight: '700',
          },
          formatter(value) {
            return value;
          },
        },
      },
    },
    plugins,
  });

  activeInsuranceCharts.set(canvas, chart);
  return chart;
}

function initAll(root = document) {
  root.querySelectorAll('canvas.insurance-dashboard-pie-chart').forEach(renderChart);
}

function observeCharts() {
  if (!document.body) {
    return;
  }

  const observer = new MutationObserver(mutations => {
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (!(node instanceof Element)) {
          continue;
        }

        if (node.matches('canvas.insurance-dashboard-pie-chart')) {
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

window.SnoInsuranceDashboardCharts = {
  init: initAll,
  render: renderChart,
  destroy: destroyChart,
};
