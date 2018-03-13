package nl.quintor.studybits.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.util.Set;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class University {

    @Id
    @GeneratedValue
    private Long id;

    private String name;

    @OneToOne
    private IndyConnection connection;

    @OneToMany(mappedBy = "university")
    public Set<Student> students;

}