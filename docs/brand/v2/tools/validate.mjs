#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(toolDirectory, "..");
const brandRoot = path.resolve(root, "..");
const assetRoot = path.join(root, "assets");
const realBrandRoot = fs.realpathSync(brandRoot);
const realAssetRoot = fs.realpathSync(assetRoot);
const pages = ["index.html", "dashboard.html", "report.html"];
const errors = [];

const htmlUrlAttributes = new Set([
    "action",
    "archive",
    "background",
    "cite",
    "codebase",
    "data",
    "formaction",
    "href",
    "longdesc",
    "manifest",
    "poster",
    "profile",
    "src",
    "usemap",
    "xlink:href"
]);

function fail(message) {
    errors.push(message);
}

function read(relativePath) {
    const target = path.resolve(root, relativePath);
    if (!isWithin(brandRoot, target) || !fs.existsSync(target)) {
        fail(`${relativePath}: file is missing or resolves outside docs/brand`);
        return "";
    }
    const realTarget = fs.realpathSync(target);
    if (!isWithin(realBrandRoot, realTarget)) {
        fail(`${relativePath}: file symlink escapes docs/brand`);
        return "";
    }
    return fs.readFileSync(realTarget, "utf8");
}

function isWithin(boundary, candidate) {
    const relative = path.relative(boundary, candidate);
    return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative));
}

function decodeHtmlEntities(value) {
    const named = {
        amp: "&",
        apos: "'",
        bsol: "\\",
        colon: ":",
        gt: ">",
        lt: "<",
        newline: "\n",
        period: ".",
        quot: '"',
        sol: "/",
        tab: "\t"
    };

    return value.replace(/&(?:#(\d+)|#x([0-9a-f]+)|([a-z]+));?/gi, (entity, decimal, hexadecimal, name) => {
        try {
            if (decimal) return String.fromCodePoint(Number.parseInt(decimal, 10));
            if (hexadecimal) return String.fromCodePoint(Number.parseInt(hexadecimal, 16));
        } catch {
            return "\0";
        }
        return named[name.toLowerCase()] ?? entity;
    });
}

function decodePath(value, sourceRelative, reference) {
    let decoded = value;
    try {
        for (let pass = 0; pass < 4; pass += 1) {
            const next = decodeURIComponent(decoded);
            if (next === decoded) break;
            decoded = next;
        }
    } catch {
        fail(`${sourceRelative}: malformed URL encoding in ${reference}`);
        return null;
    }
    return decoded;
}

function parseAttributes(tag) {
    const opening = tag.match(/^<\s*[^\s/>]+/);
    if (!opening) return [];

    const attributes = [];
    const source = tag.slice(opening[0].length, tag.endsWith(">") ? -1 : undefined);
    const pattern = /([^\s"'<>\/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;
    for (const match of source.matchAll(pattern)) {
        attributes.push({
            name: match[1].toLowerCase(),
            value: match[2] ?? match[3] ?? match[4] ?? ""
        });
    }
    return attributes;
}

function attributesByName(tag) {
    return new Map(parseAttributes(tag).map(({ name, value }) => [name, value]));
}

function targetHasId(target, fragment) {
    const extension = path.extname(target).toLowerCase();
    if (!new Set([".html", ".svg"]).has(extension)) return true;

    const source = fs.readFileSync(target, "utf8");
    for (const tag of source.matchAll(/<[a-z][^>]*>/gi)) {
        const id = attributesByName(tag[0]).get("id");
        if (id === fragment) return true;
    }
    return false;
}

function existingLocalReference(sourceRelative, reference, allowedRoot = brandRoot) {
    if (typeof reference !== "string") {
        fail(`${sourceRelative}: local reference must be a string`);
        return null;
    }

    const displayReference = reference;
    const normalized = decodeHtmlEntities(reference.trim());
    if (!normalized) return path.resolve(root, sourceRelative);
    if (/[\u0000-\u001f\u007f]/.test(normalized)) {
        fail(`${sourceRelative}: control characters are not allowed in ${displayReference}`);
        return null;
    }
    if (/&(?:#\d+|#x[0-9a-f]+|[a-z]+);?/i.test(normalized)) {
        fail(`${sourceRelative}: unresolved character entity in ${displayReference}`);
        return null;
    }

    const beforeFragment = normalized.split("#", 1)[0];
    const encodedPathname = beforeFragment.split("?", 1)[0];
    const pathname = decodePath(encodedPathname, sourceRelative, displayReference);
    if (pathname === null) return null;
    if (/^[a-z][a-z0-9+.-]*:/i.test(pathname) || normalized.startsWith("//")) {
        fail(`${sourceRelative}: external or executable reference is not allowed: ${displayReference}`);
        return null;
    }
    if (path.isAbsolute(pathname) || pathname.startsWith("/") || pathname.includes("\\")) {
        fail(`${sourceRelative}: absolute or backslash path is not allowed: ${displayReference}`);
        return;
    }

    const source = path.resolve(root, sourceRelative);
    if (!isWithin(brandRoot, source)) {
        fail(`${sourceRelative}: source resolves outside docs/brand`);
        return null;
    }
    if (!fs.existsSync(source) || !isWithin(realBrandRoot, fs.realpathSync(source))) {
        fail(`${sourceRelative}: source is missing or its symlink escapes docs/brand`);
        return null;
    }

    const target = pathname ? path.resolve(path.dirname(source), pathname) : source;
    if (!isWithin(allowedRoot, target)) {
        fail(`${sourceRelative}: reference escapes ${path.relative(brandRoot, allowedRoot) || "docs/brand"}: ${displayReference}`);
        return null;
    }
    if (!fs.existsSync(target)) {
        fail(`${sourceRelative}: missing local reference ${displayReference}`);
        return null;
    }

    const realAllowedRoot = allowedRoot === assetRoot
        ? realAssetRoot
        : allowedRoot === brandRoot
            ? realBrandRoot
            : fs.realpathSync(allowedRoot);
    const realTarget = fs.realpathSync(target);
    if (!isWithin(realAllowedRoot, realTarget)) {
        fail(`${sourceRelative}: symlink reference escapes ${path.relative(brandRoot, allowedRoot) || "docs/brand"}: ${displayReference}`);
        return null;
    }

    const fragmentStart = normalized.indexOf("#");
    if (fragmentStart >= 0 && fragmentStart < normalized.length - 1) {
        const fragment = decodePath(normalized.slice(fragmentStart + 1), sourceRelative, displayReference);
        if (fragment !== null && !targetHasId(realTarget, fragment)) {
            fail(`${sourceRelative}: dead anchor ${displayReference}`);
        }
    }

    return realTarget;
}

function scanCssReferences(sourceRelative, css) {
    if (/\bexpression\s*\(/i.test(css) || /(?:^|[;{])\s*(?:behavior|-moz-binding)\s*:/im.test(css)) {
        fail(`${sourceRelative}: executable CSS is not allowed`);
    }

    for (const match of css.matchAll(/\burl\(\s*(?:"([^"]*)"|'([^']*)'|([^'"\)]*))\s*\)/gi)) {
        existingLocalReference(sourceRelative, match[1] ?? match[2] ?? match[3]);
    }

    for (const match of css.matchAll(/@import\s+(?:"([^"]+)"|'([^']+)')/gi)) {
        existingLocalReference(sourceRelative, match[1] ?? match[2]);
    }
}

function scanSrcset(sourceRelative, value) {
    for (const candidate of value.split(",")) {
        const reference = candidate.trim().split(/\s+/, 1)[0];
        if (reference) existingLocalReference(sourceRelative, reference);
    }
}

function requiredLocalReference(sourceRelative, reference, allowedRoot = brandRoot) {
    if (typeof reference !== "string" || !reference.trim()) {
        fail(`${sourceRelative}: required local reference is missing`);
        return null;
    }
    return existingLocalReference(sourceRelative, reference, allowedRoot);
}

function validateCsp(page, html) {
    const policies = [];
    let policyPosition = -1;
    for (const match of html.matchAll(/<meta\b[^>]*>/gi)) {
        const attributes = attributesByName(match[0]);
        if (attributes.get("http-equiv")?.toLowerCase() === "content-security-policy") {
            policies.push(attributes.get("content") ?? "");
            policyPosition = match.index;
        }
    }
    if (policies.length !== 1) {
        fail(`${page}: expected exactly one Content-Security-Policy meta tag`);
        return;
    }
    const firstResource = html.search(/<(?:body|link|script|style)\b/i);
    if (firstResource >= 0 && policyPosition > firstResource) {
        fail(`${page}: CSP meta tag must precede resource-loading elements`);
    }

    const directives = new Map();
    for (const directive of policies[0].split(";")) {
        const values = directive.trim().split(/\s+/).filter(Boolean);
        if (values.length === 0) continue;
        const name = values.shift().toLowerCase();
        if (directives.has(name)) fail(`${page}: duplicate CSP directive ${name}`);
        directives.set(name, values.map((value) => value.toLowerCase()));
    }

    const requireExact = (name, expected) => {
        const actual = directives.get(name) ?? [];
        if (actual.length !== expected.length || expected.some((value) => !actual.includes(value))) {
            fail(`${page}: CSP ${name} must be ${expected.join(" ")}`);
        }
    };

    for (const name of ["default-src", "base-uri", "connect-src", "form-action", "frame-src", "media-src", "object-src", "worker-src"]) {
        requireExact(name, ["'none'"]);
    }
    for (const name of ["font-src", "img-src", "manifest-src", "script-src", "style-src"]) {
        requireExact(name, ["'self'"]);
    }
    requireExact("script-src-attr", ["'none'"]);
    requireExact("style-src-attr", ["'unsafe-inline'"]);

    const allowedDirectives = new Set([
        "base-uri", "connect-src", "default-src", "font-src", "form-action", "frame-src", "img-src",
        "manifest-src", "media-src", "object-src", "script-src", "script-src-attr", "style-src",
        "style-src-attr", "worker-src"
    ]);
    for (const name of directives.keys()) {
        if (!allowedDirectives.has(name)) fail(`${page}: unexpected CSP directive ${name}`);
    }
}

function validateHtml(page, html) {
    validateCsp(page, html);

    for (const match of html.matchAll(/<[a-z][^>]*>/gi)) {
        const tag = match[0];
        const tagName = tag.match(/^<\s*([^\s/>]+)/)?.[1].toLowerCase();
        const attributes = parseAttributes(tag);
        if (new Set(["applet", "base", "embed", "iframe", "object"]).has(tagName)) {
            fail(`${page}: active ${tagName} element is not allowed`);
        }

        for (const { name, value } of attributes) {
            if (/^on[a-z]+$/.test(name)) fail(`${page}: inline event handler ${name} is not allowed`);
            if (htmlUrlAttributes.has(name)) existingLocalReference(page, value);
            if (name === "srcset") scanSrcset(page, value);
            if (name === "ping") {
                for (const reference of value.trim().split(/\s+/)) {
                    if (reference) existingLocalReference(page, reference);
                }
            }
            if (name === "style") scanCssReferences(page, value);
            if (name === "srcdoc" || name === "xml:base") fail(`${page}: ${name} is not allowed`);
        }

        if (tagName === "meta") {
            const byName = new Map(attributes.map(({ name, value }) => [name, value]));
            if (byName.get("http-equiv")?.toLowerCase() === "refresh") fail(`${page}: meta refresh is not allowed`);
        }
    }

    for (const match of html.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style\s*>/gi)) {
        scanCssReferences(page, match[1]);
    }
    for (const match of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)) {
        if (!attributesByName(`<script ${match[1]}>`).has("src") || match[2].trim()) {
            fail(`${page}: inline script content is not allowed`);
        }
    }
}

for (const page of pages) {
    const html = read(page);
    if (!/<html\s+lang="en"/i.test(html)) fail(`${page}: missing language`);
    if ((html.match(/<h1\b/gi) ?? []).length !== 1) fail(`${page}: expected exactly one h1`);
    if (!/<main\s+id="main"/i.test(html)) fail(`${page}: missing main landmark target`);
    if (!/<a\s+class="skip-link"\s+href="#main"/i.test(html)) fail(`${page}: missing skip link`);
    if ((html.match(/data-theme-choice=/g) ?? []).length !== 3) fail(`${page}: incomplete theme control`);
    if (/<img\b(?![^>]*\balt=)[^>]*>/i.test(html)) fail(`${page}: image without alt attribute`);

    const tableCount = (html.match(/<table\b/gi) ?? []).length;
    const captionCount = (html.match(/<caption\b/gi) ?? []).length;
    if (captionCount !== tableCount) fail(`${page}: every table requires a caption`);
    validateHtml(page, html);
}

for (const cssRelative of ["assets/brand.css", "assets/tokens/tokens.css"]) {
    const css = read(cssRelative);
    if (/outline\s*:\s*(?:none|0(?:\D|$))/i.test(css)) {
        fail(`${cssRelative}: focus outlines may not be suppressed`);
    }
    scanCssReferences(cssRelative, css);
}

function readJson(relativePath) {
    try {
        return JSON.parse(read(relativePath));
    } catch (error) {
        fail(`${relativePath}: invalid JSON (${error.message})`);
        return null;
    }
}

const manifest = readJson("assets/manifest.json");
let manifestPaths = [];
if (manifest) {
    const groups = ["logo", "statusIcons", "favicon", "fonts", "tokens"];
    for (const group of groups) {
        if (!Array.isArray(manifest[group])) {
            fail(`assets/manifest.json: ${group} must be an array`);
            continue;
        }
        for (const entry of manifest[group]) {
            if (!entry || typeof entry.path !== "string" || !entry.path.trim()) {
                fail(`assets/manifest.json: ${group} entry requires a non-empty string path`);
                continue;
            }
            manifestPaths.push(entry.path);
            requiredLocalReference("assets/manifest.json", entry.path, assetRoot);
        }
    }

    requiredLocalReference("assets/manifest.json", manifest.canonicalDesign);
    requiredLocalReference("assets/manifest.json", manifest.tokenGenerator);
    requiredLocalReference("assets/manifest.json", manifest.tokenSource, assetRoot);
}
if (new Set(manifestPaths).size !== manifestPaths.length) {
    fail("assets/manifest.json: duplicate asset path");
}

const webmanifestRelative = "assets/favicon/site.webmanifest";
const webmanifest = readJson(webmanifestRelative);
if (webmanifest) {
    requiredLocalReference(webmanifestRelative, webmanifest.start_url);
    requiredLocalReference(webmanifestRelative, webmanifest.scope);
    if (!Array.isArray(webmanifest.icons)) {
        fail(`${webmanifestRelative}: icons must be an array`);
    } else {
        for (const icon of webmanifest.icons) {
            requiredLocalReference(webmanifestRelative, icon?.src, assetRoot);
        }
    }
}

function allFiles(directory) {
    return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const target = path.join(directory, entry.name);
        return entry.isDirectory() ? allFiles(target) : [target];
    });
}

for (const svgPath of allFiles(assetRoot).filter((candidate) => path.extname(candidate).toLowerCase() === ".svg")) {
    const svgRelative = path.relative(root, svgPath);
    const realSvgPath = fs.realpathSync(svgPath);
    if (!isWithin(realAssetRoot, realSvgPath)) {
        fail(`${svgRelative}: SVG symlink escapes the asset root`);
        continue;
    }
    const source = fs.readFileSync(realSvgPath, "utf8");
    if (/<!DOCTYPE\b|<!ENTITY\b|<\?(?!xml\s)/i.test(source)) {
        fail(`${svgRelative}: DTD, entity, or processing instruction is not allowed`);
    }
    if (/<\s*(?:animate|animatemotion|animatetransform|applet|audio|discard|embed|foreignobject|iframe|object|script|set|video)\b/i.test(source)) {
        fail(`${svgRelative}: active SVG content is not allowed`);
    }

    for (const tag of source.matchAll(/<[a-z][^>]*>/gi)) {
        for (const { name, value } of parseAttributes(tag[0])) {
            if (/^on[a-z]+$/.test(name)) fail(`${svgRelative}: inline event handler ${name} is not allowed`);
            if (new Set(["href", "src", "xlink:href"]).has(name)) existingLocalReference(svgRelative, value);
            if (name === "style" || /\burl\s*\(/i.test(value)) scanCssReferences(svgRelative, value);
            if (name === "xml:base") fail(`${svgRelative}: xml:base is not allowed`);
        }
    }
    scanCssReferences(svgRelative, source);
}

const themeSource = read("assets/theme.js");
const forbiddenThemePrimitives = [
    ["dynamic import", /\bimport\s*\(/],
    ["module import", /\bimport\s+(?:["'`{*]|[a-z_$])/i],
    ["eval", /\beval\s*\(/],
    ["Function constructor", /\bnew\s+Function\b|\bFunction\s*\(/],
    ["string timer", /\b(?:setInterval|setTimeout)\s*\(\s*["'`]/],
    ["network request", /\b(?:fetch|importScripts)\s*\(|\bXMLHttpRequest\b/],
    ["network channel", /\b(?:EventSource|RTCPeerConnection|WebSocket|WebTransport)\b/],
    ["beacon", /\bnavigator\s*\.\s*sendBeacon\b/],
    ["service worker", /\bnavigator\s*\.\s*serviceWorker\s*\.\s*register\b/],
    ["worker", /\bnew\s+(?:SharedWorker|Worker)\b/],
    ["navigation", /\b(?:(?:(?:document|window)\s*\.\s*)?location\s*(?:=|\.\s*(?:assign|href|replace)\s*=|\.\s*(?:assign|replace)\s*\()|window\s*\.\s*open\s*\()/],
    ["image request", /\bnew\s+Image\s*\(/],
    ["WebAssembly", /\bWebAssembly\b/],
    ["HTML injection", /\b(?:document\s*\.\s*write|insertAdjacentHTML)\s*\(|\.\s*(?:innerHTML|outerHTML)\s*=/],
    ["script creation", /\bcreateElement\s*\(\s*["'`]script["'`]\s*\)/]
];
for (const [label, pattern] of forbiddenThemePrimitives) {
    if (pattern.test(themeSource)) fail(`assets/theme.js: ${label} primitive is not allowed`);
}

const tokens = readJson("assets/tokens/tokens.json");

function rgb(hex) {
    const value = hex.replace("#", "");
    return [0, 2, 4].map((offset) => Number.parseInt(value.slice(offset, offset + 2), 16) / 255);
}

function luminance(hex) {
    const [red, green, blue] = rgb(hex).map((channel) =>
        channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
    );
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

function contrast(foreground, background) {
    const values = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
    return (values[0] + 0.05) / (values[1] + 0.05);
}

function requireContrast(label, foreground, background, minimum) {
    const ratio = contrast(foreground, background);
    if (ratio + Number.EPSILON < minimum) {
        fail(`${label}: ${ratio.toFixed(2)}:1 is below ${minimum}:1`);
    }
}

if (!tokens?.foundation || !tokens?.semantic) {
    fail("assets/tokens/tokens.json: foundation and semantic token groups are required");
} else {
    for (const theme of ["light", "dark"]) {
        const foundation = tokens.foundation[theme];
        const semantic = tokens.semantic[theme];
        if (!foundation || !semantic) {
            fail(`assets/tokens/tokens.json: missing ${theme} token theme`);
            continue;
        }
        requireContrast(`${theme} text`, foundation.text, foundation.canvas, 4.5);
        requireContrast(`${theme} secondary text`, foundation["text-secondary"], foundation.canvas, 4.5);
        requireContrast(`${theme} muted text`, foundation["text-muted"], foundation.canvas, 4.5);
        requireContrast(`${theme} brand text`, foundation["brand-text"], foundation.canvas, 4.5);
        requireContrast(`${theme} control border`, foundation["control-border"], foundation["surface-raised"], 3);
        requireContrast(`${theme} focus`, foundation.focus, foundation.canvas, 3);

        const brandValues = new Set([foundation.brand.toLowerCase(), foundation["brand-text"].toLowerCase()]);
        for (const [state, values] of Object.entries(semantic)) {
            requireContrast(`${theme} ${state} pair`, values.fg, values.bg, 4.5);
            requireContrast(`${theme} ${state} solid`, values.solid, foundation.surface, 3);
            for (const value of Object.values(values)) {
                if (brandValues.has(value.toLowerCase())) {
                    fail(`${theme} ${state}: semantic token reuses copper`);
                }
            }
        }
    }
}

if (Array.isArray(manifest?.statusIcons)) {
    for (const icon of manifest.statusIcons) {
        const iconPath = typeof icon?.path === "string" ? path.join(assetRoot, icon.path) : "";
        if (!iconPath || !fs.existsSync(iconPath)) continue;
        const source = fs.readFileSync(iconPath, "utf8");
        if (!source.includes('stroke="currentColor"')) fail(`${icon.path}: expected currentColor stroke`);
        if (/#(?:B85A28|DD8148|93451B|E9A878)/i.test(source)) fail(`${icon.path}: status icon contains copper`);
    }
}

if (errors.length > 0) {
    for (const error of errors) console.error(`- ${error}`);
    console.error(`V2 static validation failed with ${errors.length} issue${errors.length === 1 ? "" : "s"}.`);
    process.exitCode = 1;
} else {
    console.log(`V2 static validation passed: ${pages.length} pages, ${manifestPaths.length} assets, both themes.`);
}
