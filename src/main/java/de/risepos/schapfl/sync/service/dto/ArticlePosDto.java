package de.risepos.schapfl.sync.service.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@ToString
public class ArticlePosDto {
    public Long id;
    public String description;
    public BigDecimal selling_price;
    public Long articlegroup_id; // if weight EAN then = 71, if weight Article then = 69
    public Long deposit_id = 0L;
    public int min_age = 0; // beer = 16, wine = 18, vodka = 18
    public List<String> scancodes;
}
