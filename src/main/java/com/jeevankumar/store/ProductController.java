package com.jeevankumar.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final JdbcTemplate jdbc;

    public ProductController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Product> mapper = (rs, rowNum) -> (
            new Product(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getBigDecimal("price")
            ));

    @GetMapping
    public List<Product> findAll() {
        return jdbc.query(
                "SELECT id, name, price FROM products ORDER BY id",
                mapper
                );
    }

    @GetMapping("/{id}")
    public Product findById(@PathVariable long id) {
        return jdbc.queryForObject(
                "SELECT id, name, price FROM products WHERE id = ?",
                mapper,
                id
        );
    }

    @PostMapping
    public Product create(@RequestBody CreateProductRequest request) {
        return jdbc.queryForObject(
                """
                      INSERT INTO products (name, price)
                      VALUES (?, ?)
                      RETURNING id, name, price
                        """,
                mapper,
                request.name(),
                request.price()
        );
    }


    public record Product(long id, String name, BigDecimal price) {}
    public record CreateProductRequest(String name, BigDecimal price){}
}

