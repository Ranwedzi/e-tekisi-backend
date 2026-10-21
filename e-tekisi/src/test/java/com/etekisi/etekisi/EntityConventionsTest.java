package com.etekisi.etekisi;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA entities use @Getter/@Setter, not Lombok's @Data. Its all-fields
 * equals/hashCode changes as soon as a field does (a saved entity "moves" in
 * a HashSet, and lazy relations get loaded), and its toString prints every
 * column, password hashes and bank details included, into any log line that
 * mentions the entity.
 */
class EntityConventionsTest {

    @Test
    void entitiesDontGenerateEqualsHashCodeOrToString() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<BeanDefinition> entities = scanner.findCandidateComponents("com.etekisi.etekisi");
        assertThat(entities).hasSizeGreaterThan(25);

        List<String> offenders = new ArrayList<>();
        for (BeanDefinition bd : entities) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            for (String method : List.of("equals", "hashCode", "toString")) {
                boolean declared = method.equals("equals")
                        ? hasMethod(type, method, Object.class)
                        : hasMethod(type, method);
                if (declared) offenders.add(type.getSimpleName() + "." + method);
            }
        }
        assertThat(offenders).as("entities declaring equals/hashCode/toString (use @Getter @Setter, not @Data)").isEmpty();
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... params) {
        try {
            type.getDeclaredMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
