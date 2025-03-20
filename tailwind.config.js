const colors = require("tailwindcss/colors");
const defaultTheme = require("tailwindcss/defaultTheme");
const plugin = require("tailwindcss/plugin");
/** @type {import('tailwindcss').Config} */
module.exports = {
    content: {
        files: [
            "./src/**/*.clj",
            "./src/**/*.cljs",
            "./resources/public/js/widgets/**/*.js",
            "./resources/public/js/app.js",
            "!./resources/public/css/compiled",
        ],
    },
    theme: {
        extend: {
            typography: ({ theme }) => ({
                blue: {
                    css: {
                        "--tw-prose-links": colors.blue[600],
                        "--tw-prose-invert-links": colors.blue[500],
                    },
                },
            }),
            colors: {
                "sno-orange": {
                    50: "#fff7ed",
                    100: "#ffedd5",
                    200: "#fed7aa",
                    300: "#fdba74",
                    400: "#fb923c",
                    500: "#f97316",
                    600: "#ea580c",
                    700: "#c2410c",
                    800: "#9a3412",
                    900: "#7c2d12",
                },
                "sno-green": {
                    50: "#f0fdf4",
                    100: "#dcfce7",
                    200: "#bbf7d0",
                    300: "#86efac",
                    400: "#4ade80",
                    500: "#22c55e",
                    600: "#16a34a",
                    700: "#15803d",
                    800: "#166534",
                    900: "#14532d",
                },
                "sno-lime-green": {
                    50: "#f7fee7",
                    100: "#ecfccb",
                    200: "#d9f99d",
                    300: "#bef264",
                    400: "#a3e635",
                    500: "#84cc16",
                    600: "#65a30d",
                    700: "#4d7c0f",
                    800: "#3f6212",
                    900: "#365314 ",
                },
            },
            fontFamily: {
                sans: ["Inter var", ...defaultTheme.fontFamily.sans],
            },
        },
    },
    variants: {
        margin: ["responsive", "first"],
        opacity: ["responsive", "hover", "focus", "disabled"],
    },
    plugins: [
        require("@tailwindcss/typography"),
        require("@tailwindcss/forms"),
        require("@tailwindcss/aspect-ratio"),
        plugin(function ({ addVariant }) {
            addVariant("progress-unfilled", ["&::-webkit-progress-bar", "&"]);
            addVariant("progress-filled", [
                "&::-webkit-progress-value",
                "&::-moz-progress-bar",
            ]);
        }),
    ],
};
