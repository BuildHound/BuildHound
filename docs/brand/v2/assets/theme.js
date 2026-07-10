(() => {
    const storageKey = "buildhound-v2-theme";
    const choices = new Set(["auto", "light", "dark"]);
    const root = document.documentElement;
    const buttons = Array.from(document.querySelectorAll("[data-theme-choice]"));
    const themeColor = document.querySelector('meta[name="theme-color"]');

    function storedChoice() {
        try {
            const value = localStorage.getItem(storageKey);
            return choices.has(value) ? value : "auto";
        } catch {
            return "auto";
        }
    }

    function updateThemeColor() {
        if (!themeColor) return;
        themeColor.content = getComputedStyle(root).getPropertyValue("--bh-canvas").trim();
    }

    function applyTheme(choice, persist = true) {
        const safeChoice = choices.has(choice) ? choice : "auto";
        if (safeChoice === "auto") {
            root.removeAttribute("data-theme");
        } else {
            root.dataset.theme = safeChoice;
        }

        for (const button of buttons) {
            button.setAttribute("aria-pressed", String(button.dataset.themeChoice === safeChoice));
        }

        if (persist) {
            try {
                localStorage.setItem(storageKey, safeChoice);
            } catch {
                // The theme remains usable when storage is disabled or the report runs locally.
            }
        }

        requestAnimationFrame(updateThemeColor);
    }

    for (const button of buttons) {
        button.addEventListener("click", () => applyTheme(button.dataset.themeChoice));
    }

    const colorScheme = window.matchMedia("(prefers-color-scheme: dark)");
    colorScheme.addEventListener?.("change", () => {
        if (!root.hasAttribute("data-theme")) updateThemeColor();
    });

    applyTheme(storedChoice(), false);
})();
