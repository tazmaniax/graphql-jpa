package org.crygier.graphql.model.starwars

import groovy.transform.CompileStatic
import org.crygier.graphql.annotation.SchemaDocumentation

import javax.persistence.Entity
import javax.persistence.OneToMany

@Entity
@SchemaDocumentation("Represents an electromechanical robot in the Star Wars Universe")
@CompileStatic
class Droid extends Character {

    @SchemaDocumentation("Documents the primary purpose this droid serves")
    String primaryFunction;

	@SchemaDocumentation("Which humans designate this droid as their favorite")
    @OneToMany(mappedBy="favoriteDroid")
    Collection<Human> admirers;
	
}
