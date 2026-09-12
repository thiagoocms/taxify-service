package br.com.cshub.taxify.common;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractMapperTest {

    private static class UpperCaseMapper extends AbstractMapper<String, String> {
        @Override
        protected String toEntity(String source) {
            return source.toLowerCase();
        }

        @Override
        protected String toModel(String source) {
            return source.toUpperCase();
        }

        @Override
        public void merge(String source, String target) {
        }
    }

    private final UpperCaseMapper mapper = new UpperCaseMapper();

    @Test
    void toModelsMapsEveryElementOfThePage() {
        Page<String> page = new PageImpl<>(List.of("a", "b", "c"));

        Page<String> result = mapper.toModels(page);

        assertThat(result.getContent()).containsExactly("A", "B", "C");
    }

    @Test
    void toModelsPreservesEmptyPage() {
        Page<String> page = new PageImpl<>(List.of());

        Page<String> result = mapper.toModels(page);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void toEntityAndToModelAreIndependentConversions() {
        assertThat(mapper.toEntity("HELLO")).isEqualTo("hello");
        assertThat(mapper.toModel("hello")).isEqualTo("HELLO");
    }
}
