package br.com.cshub.taxify.common;

import org.springframework.data.domain.Page;

public abstract class AbstractMapper<E, M> {

    protected abstract E toEntity(M source);

    protected abstract M toModel(E source);

    public Page<M> toModels(Page<E> page) {
        return page.map(this::toModel);
    }

    public abstract void merge(E source, E target);
}
