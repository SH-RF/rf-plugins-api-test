package com.remotefalcon.plugins.api.repository;

import com.remotefalcon.library.quarkus.entity.Show;
import io.quarkus.mongodb.panache.PanacheMongoRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class ShowRepository implements PanacheMongoRepository<Show> {
    public Optional<Show> findByShowToken(String showToken) {
        return find("showToken", showToken).firstResultOptional();
    }
}
