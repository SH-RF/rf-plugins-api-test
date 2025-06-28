package com.remotefalcon.plugins.api.context;

import com.remotefalcon.library.quarkus.entity.Show;
import jakarta.enterprise.context.RequestScoped;
import lombok.Getter;
import lombok.Setter;

@RequestScoped
@Getter
@Setter
public class ShowContext {
    private Show show;
}