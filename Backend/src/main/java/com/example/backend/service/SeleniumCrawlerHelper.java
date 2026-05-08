package com.example.backend.service;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.*;

/**
 * Enhanced Selenium crawling helper with SPA support, visibility checks, and error recovery
 */
@Slf4j
public class SeleniumCrawlerHelper {

    private static final int INITIAL_WAIT_SECONDS = 20;
    private static final int RETRY_WAIT_SECONDS = 30;
    private static final long PAGE_RENDER_PAUSE_MS = 3000;

    /**
     * Wait for page to be fully loaded, with support for Angular SPA
     */
    public static void waitForPageReady(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(INITIAL_WAIT_SECONDS));

        try {
            // Step 1: Wait for document.readyState = "complete"
            wait.until(webDriver -> {
                Object readyState = ((JavascriptExecutor) webDriver)
                    .executeScript("return document.readyState");
                return "complete".equals(readyState.toString());
            });

            log.info("✓ document.readyState = complete");

            // Step 2: For Angular apps, wait for Angular to be ready
            try {
                boolean angularReady = (boolean) ((JavascriptExecutor) driver)
                    .executeScript("return typeof window.ng !== 'undefined' ? " +
                                 "window.ng.probe(document.body).injector.get('$http').pendingRequests.length === 0 " +
                                 ": true");
                if (!angularReady) {
                    log.info("⏳ Waiting for Angular to complete pending requests...");
                    wait.until(webDriver -> {
                        Object pending = ((JavascriptExecutor) webDriver)
                            .executeScript("return typeof window.ng !== 'undefined' ? " +
                                         "window.ng.probe(document.body).injector.get('$http').pendingRequests.length " +
                                         ": 0");
                        return Integer.parseInt(pending.toString()) == 0;
                    });
                    log.info("✓ Angular $http requests completed");
                }
            } catch (Exception angularEx) {
                log.debug("⚠️ Angular detection failed (may not be an Angular app, which is fine): {}", angularEx.getMessage());
            }

            // Step 3: Wait for no XHR pending (generic approach)
            try {
                wait.until(webDriver -> {
                    Object pending = ((JavascriptExecutor) webDriver)
                        .executeScript("return (window.performance && window.performance.getEntriesByType && " +
                                     "window.performance.getEntriesByType('resource').filter(r => !r.responseEnd).length === 0) ? " +
                                     "0 : window.pending_requests || 0");
                    return Integer.parseInt(pending.toString()) == 0;
                });
                log.info("✓ XHR requests completed");
            } catch (Exception xhrEx) {
                log.debug("⚠️ XHR detection failed: {}", xhrEx.getMessage());
            }

            // Step 4: Pause for rendering (React state updates, CSS animations, etc.)
            Thread.sleep(PAGE_RENDER_PAUSE_MS);
            log.info("✓ Page render pause completed");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Page ready wait interrupted");
        } catch (Exception e) {
            log.warn("⚠️ Page ready detection failed (continuing with partial readiness): {}", e.getMessage());
        }
    }

    /**
     * Check if element is visible in viewport
     */
    public static double getVisibilityPercentage(WebDriver driver, WebElement element) {
        try {
            Object result = ((JavascriptExecutor) driver)
                .executeScript(
                    "const rect = arguments[0].getBoundingClientRect();" +
                    "const viewHeight = window.innerHeight || document.documentElement.clientHeight;" +
                    "const viewWidth = window.innerWidth || document.documentElement.clientWidth;" +
                    "const visibleHeight = Math.min(rect.bottom, viewHeight) - Math.max(rect.top, 0);" +
                    "const visibleWidth = Math.min(rect.right, viewWidth) - Math.max(rect.left, 0);" +
                    "const visibleArea = visibleHeight > 0 && visibleWidth > 0 ? visibleHeight * visibleWidth : 0;" +
                    "const totalArea = rect.width * rect.height || 0;" +
                    "return totalArea > 0 ? (visibleArea / totalArea) * 100 : 0;",
                    element);
            return Double.parseDouble(result.toString());
        } catch (Exception e) {
            log.debug("Failed to calculate visibility: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Check if element is clickable (visible, enabled, not covered)
     */
    public static boolean isClickable(WebDriver driver, WebElement element) {
        try {
            // Check displayed and enabled
            if (!element.isDisplayed() || !element.isEnabled()) {
                return false;
            }

            // Check visibility percentage > 50%
            double visibility = getVisibilityPercentage(driver, element);
            if (visibility < 50) {
                return false;
            }

            // Check if element is not covered by overlay
            boolean notCovered = (boolean) ((JavascriptExecutor) driver)
                .executeScript(
                    "const rect = arguments[0].getBoundingClientRect();" +
                    "const el = document.elementFromPoint(rect.left + 5, rect.top + 5);" +
                    "return el === arguments[0] || arguments[0].contains(el);",
                    element);

            return notCovered;
        } catch (Exception e) {
            return true; // Assume clickable if we can't determine
        }
    }

    /**
     * Retry crawling with escalating patience
     */
    public static List<Map<String, String>> crawlElementsWithRetry(WebDriver driver, int maxRetries) {
        List<Map<String, String>> elements = new ArrayList<>();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            elements.clear();

            try {
                log.info("🔄 Crawl attempt {}/{}", attempt, maxRetries);

                // Wait with increasing duration
                int waitSeconds = INITIAL_WAIT_SECONDS + ((attempt - 1) * 10);
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));

                String broadSelector = "input:not([type='hidden']), button, a[href], select, textarea, " +
                                     "[role='button'], [role='link'], [role='textbox'], [role='searchbox']";

                List<WebElement> elementsList = driver.findElements(By.cssSelector(broadSelector));
                log.info("Found {} potential interactive elements", elementsList.size());

                for (WebElement el : elementsList) {
                    try {
                        boolean visible = false;
                        try {
                            visible = el.isDisplayed();
                        } catch (Exception ignored) {}

                        boolean enabled = false;
                        try {
                            enabled = el.isEnabled();
                        } catch (Exception ignored) {}

                        if (!visible && !enabled) continue;

                        Map<String, String> infoEl = new HashMap<>();
                        String tag = el.getTagName();
                        infoEl.put("tag", tag != null ? tag : "unknown");
                        infoEl.put("id", safeGetAttr(el, "id"));
                        infoEl.put("name", safeGetAttr(el, "name"));
                        infoEl.put("type", safeGetAttr(el, "type"));
                        infoEl.put("href", safeGetAttr(el, "href"));
                        infoEl.put("placeholder", safeGetAttr(el, "placeholder"));
                        infoEl.put("role", safeGetAttr(el, "role"));
                        infoEl.put("aria-label", safeGetAttr(el, "aria-label"));
                        infoEl.put("data-test", safeGetAttr(el, "data-test"));
                        infoEl.put("data-testid", safeGetAttr(el, "data-testid"));

                        // Add visibility and clickability info
                        double visibility = getVisibilityPercentage(driver, el);
                        boolean clickable = isClickable(driver, el);
                        infoEl.put("visibility_percent", String.format("%.1f", visibility));
                        infoEl.put("is_clickable", String.valueOf(clickable));

                        String text = "";
                        try {
                            text = el.getText();
                        } catch (Exception ignored) {}
                        if (text.isEmpty()) {
                            try {
                                text = safeGetAttr(el, "aria-label");
                            } catch (Exception ignored) {}
                        }
                        if (text.isEmpty()) {
                            try {
                                text = safeGetAttr(el, "value");
                            } catch (Exception ignored) {}
                        }
                        if (text.isEmpty()) {
                            try {
                                text = safeGetAttr(el, "title");
                            } catch (Exception ignored) {}
                        }
                        infoEl.put("text", text);

                        // Filter: accept inputs, buttons, links, or role attributes
                        boolean isInput = "input".equals(tag) || "textarea".equals(tag) || "select".equals(tag);
                        boolean isButton = "button".equals(tag);
                        boolean isLink = "a".equals(tag) && (!safeGetAttr(el, "href").isEmpty() || !text.isEmpty());
                        boolean isRole = !safeGetAttr(el, "role").isEmpty();

                        if ((isInput || isButton || isLink || isRole) && visibility > 0) {
                            elements.add(infoEl);
                        }
                    } catch (Exception elEx) {
                        log.debug("Element ignored: {}", elEx.getMessage());
                    }
                }

                if (!elements.isEmpty()) {
                    log.info("✓ Successfully crawled {} elements on attempt {}", elements.size(), attempt);
                    return elements;
                } else if (attempt < maxRetries) {
                    log.warn("0 elements found, retrying with more patience...");

                    // Try scrolling to trigger lazy loading
                    try {
                        ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, window.innerHeight);");
                        Thread.sleep(2000);
                        ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, -window.innerHeight);");
                    } catch (Exception ignored) {}

                    waitForPageReady(driver);
                }
            } catch (Exception e) {
                log.error("Crawl attempt {} failed: {}", attempt, e.getMessage());
            }
        }

        return elements;
    }

    /**
     * Safely get HTML attribute
     */
    public static String safeGetAttr(WebElement el, String attrName) {
        try {
            String value = el.getAttribute(attrName);
            return value != null ? value.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
