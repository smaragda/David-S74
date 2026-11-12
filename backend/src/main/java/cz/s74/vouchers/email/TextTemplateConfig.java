package cz.s74.vouchers.email;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

@Configuration
public class TextTemplateConfig {

    /** Textová alternativa e-mailu ({@code templates/mail/*.txt}); HTML šablony řeší výchozí resolver Spring Boot. */
    @Bean
    public ClassLoaderTemplateResolver textTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setResolvablePatterns(java.util.Set.of("*.txt"));
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setOrder(1);
        resolver.setCheckExistence(true);
        resolver.setCacheable(true);
        return resolver;
    }
}
