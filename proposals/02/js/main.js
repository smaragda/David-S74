// Smooth scroll pro anchor odkazy
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        const href = this.getAttribute('href');
        if (href !== '#') {
            e.preventDefault();
            const target = document.querySelector(href);
            if (target) {
                const navHeight = document.querySelector('.navbar').offsetHeight;
                const targetPosition = target.getBoundingClientRect().top + window.pageYOffset - navHeight;
                
                window.scrollTo({
                    top: targetPosition,
                    behavior: 'smooth'
                });
                
                // Zavřít Bootstrap collapse menu pokud je otevřené
                const navbarCollapse = document.getElementById('navbarNav');
                if (navbarCollapse && navbarCollapse.classList.contains('show')) {
                    const bsCollapse = new bootstrap.Collapse(navbarCollapse, {
                        toggle: false
                    });
                    bsCollapse.hide();
                }
            }
        }
    });
});

// Validace formuláře (základní)
const contactForm = document.querySelector('#kontakt form');
if (contactForm) {
    contactForm.addEventListener('submit', function(e) {
        e.preventDefault();
        
        const name = this.querySelector('input[type="text"]').value.trim();
        const email = this.querySelector('input[type="email"]').value.trim();
        const message = this.querySelector('textarea').value.trim();
        
        if (name && email && message) {
            alert('Děkujeme za vaši zprávu! Brzy se vám ozveme.');
            this.reset();
        } else {
            alert('Prosím vyplňte všechna pole.');
        }
    });
}
