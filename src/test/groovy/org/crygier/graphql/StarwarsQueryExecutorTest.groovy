package org.crygier.graphql

import org.crygier.graphql.model.starwars.Episode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.annotation.Transactional
import spock.lang.Specification

import javax.persistence.EntityManager

//@Configuration
@SpringBootTest(classes = TestApplication)
class StarwarsQueryExecutorTest extends Specification {

    @Autowired
    private GraphQLExecutor executor;

    def 'Gets just the names of all droids'() {
        given:
        def query = '''
        query HeroNameQuery {
          Droid {
            name
          }
        }
        '''
        def expected = [
                Droid: [
                        [ name: 'C-3PO' ],
                        [ name: 'R2-D2' ]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'Query for droid by name'() {
        given:
        def query = '''
        {
          Droid(name: "C-3PO") {
            name
            primaryFunction
          }
        }
        '''
        def expected = [
                Droid: [
                        [ name: 'C-3PO', primaryFunction: 'Protocol' ]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'ManyToOne Join by ID'() {
        given:
        def query = '''
        {
            Human(id: "1000") {
                name
                homePlanet
                favoriteDroid {
                    name
                }
            }
        }
        '''
        def expected = [
                Human: [
                        [name:'Luke Skywalker', homePlanet:'Tatooine', favoriteDroid:[name:'C-3PO']]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'OneToMany Join by ID'() {
        given:
        def query = '''
        {
            Human(id: "1000") {
                name
                homePlanet
                friends {
                    name
                }
            }
        }
        '''
        def expected = [
                Human: [
                        [name: 'Luke Skywalker', homePlanet: 'Tatooine', friends: [[name: 'C-3PO'], [name: 'Leia Organa'], [name: 'Han Solo'], [name: 'R2-D2']]]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'Query with parameter'() {
        given:
        def query = '''
        query humanQuery($id: String!) {
            Human(id: $id) {
                name
                homePlanet
            }
        }
        '''
        def expected = [
                Human: [
                        [name: 'Darth Vader', homePlanet: 'Tatooine']
                ]
        ]

        when:
        def result = executor.execute(query, [id: "1001"]).data

        then:
        result == expected
    }

    def 'Query with alias'() {
        given:
        def query = '''
        {
            luke: Human(id: "1000") {
                name
                homePlanet
            }
            leia: Human(id: "1003") {
                name
            }
        }
        '''
        def expected = [
                luke: [
                        [name: 'Luke Skywalker', homePlanet: 'Tatooine'],
                ],
                leia: [
                        [name: 'Leia Organa']
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'Allows us to use a fragment to avoid duplicating content'() {
        given:
        def query = """
        query UseFragment {
            luke: Human(id: "1000") {
                ...HumanFragment
            }
            leia: Human(id: "1003") {
                ...HumanFragment
            }
        }
        fragment HumanFragment on Human {
            name
            homePlanet
        }
        """
        def expected = [
                luke: [
                        [name: 'Luke Skywalker', homePlanet: 'Tatooine'],
                ],
                leia: [
                        [name: 'Leia Organa', homePlanet: 'Alderaan']
                ]
        ]
        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'Deep nesting'() {
        given:
        def query = '''
        {
            Droid(id: "2001") {
                name
                friends {
                    name
                    appearsIn
                    friends {
                        name
                    }
                }
            }
        }
        '''
        def expected = [
                Droid:[
                        [
                                name:'R2-D2',
                                friends:[
                                        [ name:'Luke Skywalker', appearsIn:['A_NEW_HOPE', 'EMPIRE_STRIKES_BACK', 'RETURN_OF_THE_JEDI', 'THE_FORCE_AWAKENS'], friends:[[name:'C-3PO'], [name:'Leia Organa'], ['name:Han Solo'], [name:'R2-D2']]],
                                        [ name:'Han Solo', appearsIn:['A_NEW_HOPE', 'EMPIRE_STRIKES_BACK', 'RETURN_OF_THE_JEDI', 'THE_FORCE_AWAKENS'], friends:[[name:'Leia Organa'], [name:'Luke Skywalker'], [name:'R2-D2']]],
                                        [ name:'Leia Organa', appearsIn:['A_NEW_HOPE', 'EMPIRE_STRIKES_BACK', 'RETURN_OF_THE_JEDI', 'THE_FORCE_AWAKENS'], friends:[[name:'C-3PO'], [name:'Luke Skywalker'], [name:'Han Solo'], [name:'R2-D2']]]
                                ]
                        ]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result.toString() == expected.toString()
    }

    def 'Pagination at the root'() {
        given:
        def query = '''
        {
            HumanConnection(paginationRequest: { page: 1, size: 2 }) {
                totalPages
                totalElements
                content {
                    name
                }
            }
        }
        '''
        def expected = [
                HumanConnection: [
                        totalPages: 3,
                        totalElements: 6,
                        content: [
                                [ name: 'Darth Vader' ],
                                [ name: 'Luke Skywalker' ]
                        ]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'Pagination without content'() {
        given:
        def query = '''
        {
            HumanConnection(paginationRequest: { page: 1, size: 2}) {
                totalPages
                totalElements
            }
        }
        '''

        def expected = [
                HumanConnection: [
                        totalPages: 3,
                        totalElements: 6
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'Ordering Fields'() {
        given:
        def query = '''
        {
            Human {
                name(orderBy: DESC)
                homePlanet
            }
        }
        '''
        def expected = [
                Human: [
                    [ name: 'Wilhuff Tarkin', homePlanet: null],
                    [ name: 'Luke Skywalker', homePlanet: "Tatooine"],
                    [ name: 'Leia Organa', homePlanet: "Alderaan"],
                    [ name: 'Han Solo', homePlanet: null],
                    [ name: 'Darth Vader', homePlanet: "Tatooine"],
                    [ name: 'Darth Maul', homePlanet: null]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'Query by Collection of Enums at root level'() {
        // Semi-proper JPA: select distinct h from Human h join h.appearsIn ai where ai in (:episodes)

		//This test raises an inconsistency, for other "associations", the filter will be on the appearsIn field itself
		//Additionally, taking the nested filtering into account, only the specific Episode would be returned in appearsIn, not all of them
		
        given:
        def query = '''
        {
          Human(appearsIn: [THE_FORCE_AWAKENS]) {
            name
            appearsIn
          }
        }
        '''
        def expected = [
                Human: [
                    [ name: 'Luke Skywalker', appearsIn: [Episode.A_NEW_HOPE, Episode.EMPIRE_STRIKES_BACK, Episode.RETURN_OF_THE_JEDI, Episode.THE_FORCE_AWAKENS]],
                    [ name: 'Han Solo', appearsIn: [Episode.A_NEW_HOPE, Episode.EMPIRE_STRIKES_BACK, Episode.RETURN_OF_THE_JEDI, Episode.THE_FORCE_AWAKENS] ],
                    [ name: 'Leia Organa', appearsIn: [Episode.A_NEW_HOPE, Episode.EMPIRE_STRIKES_BACK, Episode.RETURN_OF_THE_JEDI, Episode.THE_FORCE_AWAKENS] ],
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected;
    }

    def 'Query by restricting sub-object'() {
        given:
		
		//since gender is mapped as an association, the (new) default behavior of outer joins will cause all humans to be
		//returned regardless of their gender.  To only return the Men, we have to make gender an inner join.
		
        def query = '''
        {
          Human {
            name
            gender(joinType: INNER, code: "Male") {
              description
            }
          }
        }
        '''
		//the resulting order of this query is different based upon join vs fetch
        def expected = [
                Human: [
                        [ name: 'Darth Vader', gender: [ description: "Male" ] ],
                        [ name: 'Darth Maul', gender: [ description: "Male" ] ],
                        [ name: 'Luke Skywalker', gender: [ description: "Male" ]],
                        [ name: 'Han Solo', gender: [ description: "Male" ] ],
                        [ name: 'Wilhuff Tarkin', gender: [ description: "Male" ] ],
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected;
    }

    def 'Query for searching by IntType (sequence field)'() {
        given:
        def query = '''
        {
          CodeList(sequence: 2) {
            id
            description
            active
            type
            sequence
          }
        }
        '''
        def expected = [
            CodeList: [
                [ id: 1, description: "Female", active: true, type: "org.crygier.graphql.model.starwars.Gender", sequence: 2]
            ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected;
    }

    def 'Query for searching by BooleanType (active field)'() {
        given:
        def query = '''
        {
          CodeList(active: true) {
            id
            description
            active
            type
            sequence
          }
        }
        '''
        def expected = [
                CodeList: [
                        [ id: 0, description: "Male", active: true, type: "org.crygier.graphql.model.starwars.Gender", sequence: 1],
                        [ id: 1, description: "Female", active: true, type: "org.crygier.graphql.model.starwars.Gender", sequence: 2]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected;
    }

    def 'ManyToMany test filter'() {
        given:
        def query = '''
        {
            Human(name: "Luke Skywalker") {
                name
                homePlanet
                friends(name: "Han Solo") {
                    name
                }
            }
        }
        '''
        def expected = [
                Human: [
                        [name: 'Luke Skywalker', homePlanet: 'Tatooine', friends: [[name: 'Han Solo']]]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }
	
	def 'Outer Join Nested Objects by Default'() {
        given:
        def query = '''
        {
            Human(name: "Darth Maul") {
                name
                homePlanet
                friends {
                    name
                }
            }
        }
        '''
        def expected = [
                Human: [
                        [name: 'Darth Maul', homePlanet: null, friends: []]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }
	
	def 'Inner Join Nested Objects'() {
        given:
        def query = '''
        {
            Human(name: "Darth Maul") {
                name
                homePlanet
                friends (joinType: INNER)  {
                    name
                }
            }
        }
        '''
        def expected = [
                Human: []
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }
	
	def 'Outer Join Nested Objects with Filters'() {
		//the idea is that since friends is a left join, the filter criteria should be applied to the join, not the where
		
        given:
        def query = '''
        {
            Human(name: "Darth Maul") {
                name
                homePlanet
                friends(name: "Luke Skywalker") {
                    name
                }
            }
        }
        '''
        def expected = [
                Human: [
                        [name: 'Darth Maul', homePlanet: null, friends: []]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }
	
	def 'Inner Join Nested Objects with Filters'() {
        given:
        def query = '''
        {
            Human(name: "Darth Maul") {
                name
                homePlanet
                friends(joinType: INNER, name: "Luke Skywalker") {
                    name
                }
            }
        }
        '''
        def expected = [
                Human:[]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'OneToMany test'() {
        given:
        def query = '''
        {
          Droid(name: "C-3PO") {
            name
            primaryFunction
			admirers {
				name
			}
		  }
        }
        '''
        def expected = [
                Droid: [
                        [ name: 'C-3PO', primaryFunction: 'Protocol', admirers:[[name: "Luke Skywalker"], [name:"Leia Organa"]]]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }

    def 'OneToMany test filter'() {
        given:
        def query = '''
        {
          Droid(name: "C-3PO") {
            name
            primaryFunction
			admirers(homePlanet: "Tatooine") {
				name
			}
		  }
        }
        '''
        def expected = [
                Droid: [
                        [ name: 'C-3PO', primaryFunction: 'Protocol', admirers:[[name: "Luke Skywalker"]]]
                ]
        ]

        when:
        def result = executor.execute(query).data

        then:
        result == expected
    }
	
	//TODO: Add tests to assert nested fetching

    @Autowired
    private EntityManager em;

    @Transactional
    def 'JPA Sample Tester'() {
        when:
        //def query = em.createQuery("select h.id, h.name, h.gender.description from Human h where h.gender.code = 'Male'");
        def query = em.createQuery("select h, h.friends from Human h");
        //query.setParameter(1, Episode.THE_FORCE_AWAKENS);
        //query.setParameter("episodes", EnumSet.of(Episode.THE_FORCE_AWAKENS));
        def result = query.getResultList();
        //println JsonOutput.prettyPrint(JsonOutput.toJson(result));

        then:
        result;
    }

}
