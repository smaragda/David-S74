"""Screenshot tour of David S74 website."""
import os
import subprocess
import time
from playwright.sync_api import sync_playwright

OUTPUT_DIR = "/tmp/david-s74-screenshots"
os.makedirs(OUTPUT_DIR, exist_ok=True)

BASE_URL = "http://localhost:8765"

def shot(page, name):
    path = f"{OUTPUT_DIR}/{name}.png"
    page.screenshot(path=path, full_page=False)
    print(f"  saved: {path}")

server = subprocess.Popen(
    ["python3", "-m", "http.server", "8765"],
    cwd="/Users/janmarcis/IdeaProjects/David-S74",
    stdout=subprocess.DEVNULL,
    stderr=subprocess.DEVNULL,
)
time.sleep(1)

try:
    with sync_playwright() as p:
        # --- Desktop (1440px) ---
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        page.goto(BASE_URL)
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(1500)  # extra čas pro async JS

        # 1. Hero / Home
        page.evaluate("document.querySelector('#home').scrollIntoView()")
        page.wait_for_timeout(400)
        shot(page, "01-home")

        # 2. About
        page.evaluate("document.querySelector('#about').scrollIntoView()")
        page.wait_for_timeout(400)
        shot(page, "02-about")

        # 3. Wine cards
        try:
            page.wait_for_selector(".wine-card", timeout=5000)
            page.locator(".wine-card").first.scroll_into_view_if_needed()
            page.wait_for_timeout(400)
            shot(page, "03-wine-cards")
        except Exception:
            print("  skipped: .wine-card not found within 5s")

        # 4. Events (rezervační panel je na desktopu přímo viditelný)
        page.evaluate("document.querySelector('#events').scrollIntoView()")
        page.wait_for_timeout(600)
        shot(page, "04-events")

        # 5. Contact
        page.evaluate("document.querySelector('#contact').scrollIntoView()")
        page.wait_for_timeout(400)
        shot(page, "05-contact")

        # 6. Footer
        page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
        page.wait_for_timeout(400)
        shot(page, "06-footer")

        # 7. Menu lightbox
        page.evaluate("document.querySelector('#menu-btn').click()")
        page.wait_for_timeout(800)
        shot(page, "07-menu-lightbox")
        page.keyboard.press("Escape")
        page.wait_for_timeout(300)

        # 8. Kava lightbox
        page.evaluate("document.querySelector('#kava-card').click()")
        page.wait_for_timeout(800)
        shot(page, "08-kava-lightbox")
        page.keyboard.press("Escape")
        page.wait_for_timeout(300)

        # 9. Wine lightbox
        try:
            wine_card = page.locator(".wine-card").first
            wine_card.click()
            page.wait_for_timeout(800)
            shot(page, "09-wine-lightbox")
            page.keyboard.press("Escape")
            page.wait_for_timeout(300)
        except Exception:
            print("  skipped: wine lightbox")

        browser.close()

        # --- Mobile (390px) - toggle tlačítko je viditelné ---
        browser2 = p.chromium.launch(headless=True)
        page2 = browser2.new_page(viewport={"width": 390, "height": 844})
        page2.goto(BASE_URL)
        page2.wait_for_load_state("networkidle")
        page2.wait_for_timeout(1500)

        page2.evaluate("document.querySelector('#home').scrollIntoView()")
        page2.wait_for_timeout(400)
        shot(page2, "10-mobile-home")

        page2.evaluate("document.querySelector('#events').scrollIntoView()")
        page2.wait_for_timeout(500)
        shot(page2, "11-mobile-events")

        # Toggle reserve panel na mobilu
        try:
            toggle = page2.locator("#reserve-toggle-btn")
            toggle.scroll_into_view_if_needed()
            page2.wait_for_timeout(300)
            toggle.click()
            page2.wait_for_timeout(1000)
            shot(page2, "12-mobile-reserve-panel")
        except Exception as e:
            print(f"  skipped: mobile reserve toggle ({e})")

        browser2.close()

finally:
    server.terminate()

print(f"\nDone. Screenshots in: {OUTPUT_DIR}")
