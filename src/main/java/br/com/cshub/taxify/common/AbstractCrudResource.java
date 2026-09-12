package br.com.cshub.taxify.common;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;

public abstract class AbstractCrudResource<ID extends Serializable, M, F> {

    protected final AbstractCrudService<?, ID, M, F> service;

    protected AbstractCrudResource(AbstractCrudService<?, ID, M, F> service) {
        this.service = service;
    }

//    @GetMapping
//    public ResponseEntity<Page<M>> getAll(Pageable pageable) {
//        var page = service.findAll(pageable);
//        return ResponseEntity.ok(page);
//    }

    @GetMapping
    public ResponseEntity<Page<M>> getAll(Pageable pageable, F filter) {
        var page = service.findAll(pageable, filter);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{id}")
    public ResponseEntity<M> getById(@PathVariable ID id) {
        var dto = service.findById(id);
        return ResponseEntity.ok(dto);
    }

    @PostMapping
    public ResponseEntity<M> create(@Valid @RequestBody M dto) {
        dto = service.create(dto);
        return ResponseEntity.ok(dto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<M> update(@PathVariable ID id, @Valid @RequestBody M dto) {
        dto = service.update(id, dto);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable ID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}

