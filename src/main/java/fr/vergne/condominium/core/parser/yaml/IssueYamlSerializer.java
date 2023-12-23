package fr.vergne.condominium.core.parser.yaml;

import java.util.function.Function;

import fr.vergne.condominium.core.monitorable.Issue;
import fr.vergne.condominium.core.monitorable.Monitorable;
import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.source.Source.Refiner;
import fr.vergne.condominium.core.util.RefinerIdSerializer;
import fr.vergne.condominium.core.util.Serializer;

public interface IssueYamlSerializer {

	public static Serializer<Issue, String> create(Function<Source<?>, Source.Track> sourceTracker,
			Serializer<Source<?>, String> sourceSerializer, Serializer<Refiner<?, ?, ?>, String> refinerSerializer,
			RefinerIdSerializer refinerIdSerializer) {
		Class<Issue> monitorableClass = Issue.class;
		Monitorable.Factory<Issue, Issue.State> monitorableFactory = Issue::create;
		Serializer<Issue.State, String> stateSerializer = new Serializer<Issue.State, String>() {

			@Override
			public String serialize(Issue.State state) {
				return state.name().toLowerCase();
			}

			@Override
			public Issue.State deserialize(String serial) {
				return Issue.State.valueOf(serial.toUpperCase());
			}
		};
		return MonitorableYamlSerializer.create(monitorableClass, monitorableFactory, stateSerializer, sourceTracker,
				sourceSerializer, refinerSerializer, refinerIdSerializer);
	}
}
