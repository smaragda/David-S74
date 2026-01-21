// Hamburger menu toggle
const menuToggle = document.getElementById('menu-toggle');
const menu = document.getElementById('menu');

if (menuToggle) {
    menuToggle.addEventListener('click', () => {
        menu.classList.toggle('active');
        
        // Animace hamburger menu
        const spans = menuToggle.querySelectorAll('span');
        if (menu.classList.contains('active')) {
            spans[0].style.transform = 'rotate(45deg) translate(8px, 8px)';
            spans[1].style.opacity = '0';
            spans[2].style.transform = 'rotate(-45deg) translate(7px, -7px)';
        } else {
            spans[0].style.transform = 'none';
            spans[1].style.opacity = '1';
            spans[2].style.transform = 'none';
        }
    });
}

// Smooth scroll pro anchor odkazy
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        e.preventDefault();
        const target = document.querySelector(this.getAttribute('href'));
        if (target) {
            const navHeight = document.querySelector('.nav').offsetHeight;
            const targetPosition = target.getBoundingClientRect().top + window.pageYOffset - navHeight;
            
            window.scrollTo({
                top: targetPosition,
                behavior: 'smooth'
            });
            
            // Zavřít mobilní menu po kliknutí
            if (menu && menu.classList.contains('active')) {
                menu.classList.remove('active');
                const spans = menuToggle.querySelectorAll('span');
                spans[0].style.transform = 'none';
                spans[1].style.opacity = '1';
                spans[2].style.transform = 'none';
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
