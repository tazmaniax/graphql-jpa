package org.crygier.graphql

import graphql.Scalars
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLSchema
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import spock.lang.Specification

import javax.persistence.EntityManager

@Configuration
@SpringBootTest(classes = TestApplication)
class StarwarsSchemaBuildTest extends Specification {

    @Autowired
    private EntityManager entityManager;

    private GraphQLSchemaBuilder builder;

    void setup() {
        builder = new GraphQLSchemaBuilder(entityManager);
    }

    def 'Correctly derives the schema from Given Entities'() {
		given:
		def type = new GraphQLList(Scalars.GraphQLString)
		
        when:
        GraphQLSchema schema = builder.getGraphQLSchema();

        then:   "Ensure the result is returned"
        schema;

        then:   "Ensure that collections can be queried on"
        schema.getQueryType().getFieldDefinition("Droid").getArgument("appearsIn")

        then:   "Ensure Subobjects may be queried upon"
        schema.getQueryType().getFieldDefinition("CodeList").getArguments().size() == 6
        schema.getQueryType().getFieldDefinition("CodeList").getArgument("code").getType() == type
    }

}
